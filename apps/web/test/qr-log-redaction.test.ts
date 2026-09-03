import { spawnSync } from "node:child_process";
import { resolve } from "node:path";
import { expect, it } from "vitest";

it("preloads QR URL redaction for Next-style errors while preserving diagnostics", () => {
  const preload = resolve("qr-log-redaction.mjs");
  const token = "synthetic_".repeat(5);
  const result = spawnSync(process.execPath, ["--import", preload, "--eval", `
    const token = ${JSON.stringify(token)};
    console.log("ordinary startup: %s", "ready");
    console.error("Failed to proxy http://api.test/api/v1/public/meal-usage-qr/" + token + "/meal-usages?secret=hidden", new Error("ECONNRESET"));
    console.error(new Error("Failed to handle request for /qr/" + token + "#hidden"));
    console.warn({ cause: { url: "/api/v1/public/meal-usage-qr/" + token.replaceAll("s", "%73") } });
  `], { encoding: "utf8" });
  expect(result.status).toBe(0);
  const output = result.stdout + result.stderr;
  expect(output.match(/\[REDACTED\]/g)).toHaveLength(3);
  expect(output).not.toContain(token);
  expect(output).not.toContain("secret=hidden");
  expect(output).not.toContain("#hidden");
  expect(output).toContain("ordinary startup: ready");
  expect(output).toContain("ECONNRESET");
  expect(output).toContain("/api/v1/public/meal-usage-qr/[REDACTED]");
  expect(output).toContain("/qr/[REDACTED]");
});
