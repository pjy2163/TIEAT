import { setTimeout as delay } from "node:timers/promises";
import { pathToFileURL } from "node:url";

const armOrigin = "https://management.azure.com";
const apiVersion = "2025-01-01";

async function requestJson(url, options = {}) {
  const response = await fetch(url, { ...options, redirect: "error", signal: AbortSignal.timeout(20_000) });
  if (!response.ok) throw new Error(`HTTP_${response.status}`);
  const body = await response.text();
  return body ? JSON.parse(body) : null;
}

export async function runScaleSchedule(env = process.env) {
  const desired = Number(env.TIEAT_MIN_REPLICAS);
  const start = Number(env.TIEAT_ACTIVE_START_KST);
  const end = Number(env.TIEAT_ACTIVE_END_KST);
  const targets = JSON.parse(env.TIEAT_SCALE_TARGETS ?? "null");
  const resourceId = /^\/subscriptions\/[0-9a-f-]{36}\/resourceGroups\/[a-z0-9_.()-]+\/providers\/Microsoft\.App\/containerApps\/[a-z0-9-]+$/i;
  if (!/^[01]$/.test(env.TIEAT_MIN_REPLICAS ?? "")
    || ![start, end].every(value => Number.isInteger(value) && value >= 0 && value < 1440)
    || start === end || !Array.isArray(targets) || targets.length !== 2 || new Set(targets).size !== 2
    || !targets.every(value => typeof value === "string" && resourceId.test(value))
    || !env.TIEAT_SCALE_ENVIRONMENT_ID || !env.AZURE_CLIENT_ID) {
    throw new Error("INVALID_SCALE_CONFIGURATION");
  }
  const outsideWindow = () => {
    const now = new Date();
    const minute = ((now.getUTCHours() + 9) % 24) * 60 + now.getUTCMinutes();
    const active = start < end ? minute >= start && minute < end : minute >= start || minute < end;
    return desired !== Number(active);
  };
  // A delayed/retried midnight job must never turn off the next day's warm floor.
  if (outsideWindow()) {
    console.info("operational_event=scale_schedule_skipped reason=outside_window");
    return;
  }

  const identityUrl = new URL(env.IDENTITY_ENDPOINT);
  identityUrl.searchParams.set("api-version", "2019-08-01");
  identityUrl.searchParams.set("resource", `${armOrigin}/`);
  identityUrl.searchParams.set("client_id", env.AZURE_CLIENT_ID);
  if (!env.IDENTITY_HEADER) throw new Error("MISSING_MANAGED_IDENTITY");
  const identity = await requestJson(identityUrl, { headers: { "X-IDENTITY-HEADER": env.IDENTITY_HEADER } });
  if (!identity?.access_token) throw new Error("INVALID_IDENTITY_RESPONSE");
  const headers = { Authorization: `Bearer ${identity.access_token}`, "Content-Type": "application/json" };
  const urls = targets.map(target => `${armOrigin}${target}?api-version=${apiVersion}`);
  const current = await Promise.all(urls.map(url => requestJson(url, { headers })));

  // Validate both targets before any write. Do not modify images, secrets, ingress, or other limits.
  for (const app of current) {
    const properties = app.properties;
    const scale = properties?.template?.scale;
    if (!app.location || properties?.environmentId?.toLowerCase() !== env.TIEAT_SCALE_ENVIRONMENT_ID.toLowerCase()
      || properties?.configuration?.activeRevisionsMode !== "Single"
      || properties?.provisioningState !== "Succeeded"
      || scale?.maxReplicas !== 1 || !scale.rules?.some(rule => rule.http)
      || scale.rules.some(rule => rule.custom?.type === "cron")) {
      throw new Error("UNEXPECTED_APP_CONFIGURATION");
    }
  }

  const deadline = Date.now() + 180_000;
  const results = await Promise.allSettled(urls.map(async (url, index) => {
    const properties = current[index].properties;
    if (properties.template.scale.minReplicas !== desired) {
      const latest = await requestJson(url, { headers });
      if (latest.properties.latestRevisionName !== properties.latestRevisionName
        || latest.properties.provisioningState !== "Succeeded") throw new Error("CONCURRENT_DEPLOYMENT");
      if (outsideWindow()) {
        console.info("operational_event=scale_schedule_skipped reason=outside_window");
        return;
      }
      // JSON Merge Patch preserves concurrent image/env/probe updates server-side.
      // Clear a custom suffix so Azure can name the new revision automatically.
      const template = { revisionSuffix: null, scale: { minReplicas: desired } };
      await requestJson(url, { method: "PATCH", headers, body: JSON.stringify({ location: current[index].location, properties: { template } }) });
    }
    while (Date.now() < deadline) {
      const checked = (await requestJson(url, { headers })).properties;
      if (checked.provisioningState === "Failed") throw new Error("REVISION_FAILED");
      if (checked.provisioningState === "Succeeded" && checked.template.scale.minReplicas === desired
        && checked.latestReadyRevisionName && checked.latestReadyRevisionName === checked.latestRevisionName) {
        console.info(`operational_event=scale_schedule_ready target=${index} min=${desired}`);
        return;
      }
      await delay(3_000);
    }
    throw new Error("REVISION_READY_TIMEOUT");
  }));
  const failure = results.find(result => result.status === "rejected");
  if (failure) throw failure.reason;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  runScaleSchedule().catch(error => {
    const code = /^[A-Z][A-Z0-9_]+$/.test(error?.message ?? "") ? error.message : "REQUEST_FAILED";
    // Never print ARM responses, access tokens, URLs, or nested exception causes.
    console.error(`operational_event=scale_schedule_failed code=${code}`);
    process.exitCode = 1;
  });
}
