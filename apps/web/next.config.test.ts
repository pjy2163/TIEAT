import { describe, expect, it } from "vitest";
import nextConfig, { buildSecurityHeaders, resolveApiOrigin } from "./next.config";

describe("Next web configuration", () => {
  it("keeps the localhost API fallback outside production", () => {
    expect(resolveApiOrigin(undefined, "development")).toBe("http://localhost:8080");
  });

  it("requires an explicit API origin for production", () => {
    expect(() => resolveApiOrigin(undefined, "production")).toThrow(
      "TIEAT_API_ORIGIN is required for production builds.",
    );
    expect(() => resolveApiOrigin("", "production")).toThrow(
      "TIEAT_API_ORIGIN must be an absolute http(s) origin",
    );
  });

  it.each([
    "http://api.internal:8080",
    "https://api.example.test",
    "https://api.example.test/",
  ])("accepts an absolute HTTP(S) origin: %s", (origin) => {
    expect(resolveApiOrigin(origin, "production")).toBe(origin.replace(/\/$/, ""));
  });

  it.each([
    "ftp://api.example.test",
    "api.example.test:8080",
    "http://user:password@api.example.test",
    "http://api.example.test/api",
    "http://api.example.test?query=value",
    "http://api.example.test#fragment",
    "http://localhost:8080",
    "http://127.0.0.1:8080",
    "http://[::1]:8080",
    "",
  ])("rejects a non-origin API value: %s", (origin) => {
    expect(() => resolveApiOrigin(origin, "production")).toThrow(
      "TIEAT_API_ORIGIN must be an absolute http(s) origin",
    );
  });

  it("keeps CSP report-only and omits HSTS outside production", () => {
    for (const nodeEnv of ["development", "test"]) {
      const headers = buildSecurityHeaders(nodeEnv);
      expect(headers[0]).toMatchObject({ source: "/:path*" });
      expect(headers[0].headers).toEqual([
        {
          key: "Content-Security-Policy-Report-Only",
          value: "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; frame-src 'self' blob:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'",
        },
        { key: "Referrer-Policy", value: "no-referrer" },
        { key: "X-Content-Type-Options", value: "nosniff" },
        { key: "X-Frame-Options", value: "DENY" },
        {
          key: "Permissions-Policy",
          value: "camera=(), microphone=(), geolocation=()",
        },
      ]);
      expect(headers[0].headers).not.toContainEqual({ key: "Strict-Transport-Security", value: "max-age=31536000" });
    }
  });

  it("adds HSTS only to production global headers", () => {
    const headers = buildSecurityHeaders("production")[0].headers;
    expect(headers).toContainEqual({ key: "Strict-Transport-Security", value: "max-age=31536000" });
    expect(headers).toContainEqual(expect.objectContaining({ key: "Content-Security-Policy" }));
    expect(headers).not.toContainEqual(expect.objectContaining({ key: "Content-Security-Policy-Report-Only" }));
    expect(headers).toContainEqual({ key: "Referrer-Policy", value: "no-referrer" });
    expect(headers).toContainEqual({ key: "X-Content-Type-Options", value: "nosniff" });
    expect(headers).toContainEqual({ key: "X-Frame-Options", value: "DENY" });
    expect(headers).toContainEqual({
      key: "Permissions-Policy",
      value: "camera=(), microphone=(), geolocation=()",
    });
  });

  it("keeps QR-only cache and robots boundaries without duplicating global headers", async () => {
    expect(buildSecurityHeaders("test")).toEqual([
      expect.any(Object),
      {
        source: "/qr/:token",
        headers: [
          { key: "Cache-Control", value: "no-store, max-age=0" },
          { key: "X-Robots-Tag", value: "noindex, nofollow, noarchive" },
        ],
      },
    ]);

    await expect(nextConfig.headers?.()).resolves.toEqual(buildSecurityHeaders("test"));
  });
});
