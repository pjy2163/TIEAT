import { describe, expect, it } from "vitest";
import nextConfig, { resolveApiOrigin } from "./next.config";

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

  it("limits privacy headers to the public QR route", async () => {
    await expect(nextConfig.headers?.()).resolves.toEqual([
      {
        source: "/qr/:token",
        headers: [
          { key: "Cache-Control", value: "no-store, max-age=0" },
          { key: "Referrer-Policy", value: "no-referrer" },
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "X-Frame-Options", value: "DENY" },
          { key: "X-Robots-Tag", value: "noindex, nofollow, noarchive" },
          {
            key: "Permissions-Policy",
            value: "camera=(), microphone=(), geolocation=()",
          },
        ],
      },
    ]);
  });
});
