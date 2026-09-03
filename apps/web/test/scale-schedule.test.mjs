import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { runScaleSchedule } from "../../../infra/azure/set-min-replicas.mjs";

const prefix = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/simulation/providers/Microsoft.App";
const targets = [`${prefix}/containerApps/web`, `${prefix}/containerApps/api`];
const env = {
  TIEAT_SCALE_TARGETS: JSON.stringify(targets), TIEAT_MIN_REPLICAS: "1",
  TIEAT_ACTIVE_START_KST: "475", TIEAT_ACTIVE_END_KST: "0",
  TIEAT_SCALE_ENVIRONMENT_ID: `${prefix}/managedEnvironments/test`,
  AZURE_CLIENT_ID: "synthetic-client", IDENTITY_ENDPOINT: "http://identity.test/token", IDENTITY_HEADER: "synthetic-header",
};
const morning = new Date("2026-09-03T22:55:00Z");
const app = () => ({ location: "koreacentral", properties: {
  environmentId: env.TIEAT_SCALE_ENVIRONMENT_ID, configuration: { activeRevisionsMode: "Single" },
  provisioningState: "Succeeded", latestRevisionName: "r0", latestReadyRevisionName: "r0",
  template: { revisionSuffix: "old", containers: [{ image: "immutable-image", env: [{ name: "DB_PASSWORD", secretRef: "db-password" }] }],
    scale: { minReplicas: 0, maxReplicas: 1, rules: [{ name: "http", http: { metadata: { concurrentRequests: "10" } } }] } },
} });
beforeEach(() => { vi.useFakeTimers({ toFake: ["Date"] }); vi.setSystemTime(morning); });
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); });

it("changes only the real floor at both KST boundaries, preserves the template, and safely repeats", async () => {
  const apps = targets.map(app);
  const patches = [];
  const request = vi.fn(async (input, options = {}) => {
    const url = new URL(input);
    if (url.hostname === "identity.test") return Response.json({ access_token: "synthetic-access" });
    const index = targets.indexOf(url.pathname);
    expect(index).toBeGreaterThanOrEqual(0);
    if (options.method === "PATCH") {
      const body = JSON.parse(options.body);
      patches.push(body);
      expect(body).toEqual({ location: "koreacentral", properties: { template: { revisionSuffix: null, scale: { minReplicas: expect.any(Number) } } } });
      // Another deployment changes the image after the scheduler's last GET.
      apps[index].properties.template.containers[0].image = "concurrent-image";
      Object.assign(apps[index].properties.template.scale, body.properties.template.scale);
      delete apps[index].properties.template.revisionSuffix;
      apps[index].properties.latestRevisionName = `r${patches.length}`;
      apps[index].properties.latestReadyRevisionName = `r${patches.length}`;
      return new Response(null, { status: 202 });
    }
    return Response.json(apps[index]);
  });
  vi.stubGlobal("fetch", request);
  await runScaleSchedule(env);
  await runScaleSchedule(env);
  expect(patches).toHaveLength(2);
  expect(apps.every(value => value.properties.template.containers[0].image === "concurrent-image")).toBe(true);
  expect(apps[0].properties.template.scale).toEqual({ ...app().properties.template.scale, minReplicas: 1 });
  vi.setSystemTime(new Date("2026-09-04T15:00:00Z"));
  await runScaleSchedule({ ...env, TIEAT_MIN_REPLICAS: "0" });
  expect(patches).toHaveLength(4);
  expect(patches.map(value => value.properties.template.scale.minReplicas)).toEqual([1, 1, 0, 0]);
  expect(apps.every(value => value.properties.template.scale.minReplicas === 0)).toBe(true);
  const calls = request.mock.calls.length;
  vi.setSystemTime(morning);
  await runScaleSchedule({ ...env, TIEAT_MIN_REPLICAS: "0" });
  expect(request).toHaveBeenCalledTimes(calls);
});

it("does not write when either target or the execution time becomes unsafe", async () => {
  const bad = app();
  bad.properties.template.scale.maxReplicas = 2;
  const request = vi.fn()
    .mockResolvedValueOnce(Response.json({ access_token: "synthetic-access" }))
    .mockResolvedValueOnce(Response.json(app()))
    .mockResolvedValueOnce(Response.json(bad));
  vi.stubGlobal("fetch", request);
  await expect(runScaleSchedule(env)).rejects.toThrow("UNEXPECTED_APP_CONFIGURATION");
  expect(request.mock.calls.some(([, options]) => options.method === "PATCH")).toBe(false);
  vi.setSystemTime(new Date("2026-09-03T22:54:59Z"));
  request.mockReset().mockImplementation(async input => {
    if (new URL(input).hostname === "identity.test") {
      vi.setSystemTime(morning);
      return Response.json({ access_token: "synthetic-access" });
    }
    const warm = app();
    warm.properties.template.scale.minReplicas = 1;
    return Response.json(warm);
  });
  await runScaleSchedule({ ...env, TIEAT_MIN_REPLICAS: "0" });
  expect(request.mock.calls.some(([, options]) => options.method === "PATCH")).toBe(false);
});
