import type { NextConfig } from "next";

const DEVELOPMENT_API_ORIGIN = "http://localhost:8080";
const INVALID_API_ORIGIN_MESSAGE =
  "TIEAT_API_ORIGIN must be an absolute http(s) origin without credentials, query, fragment, or a non-root path.";

export function resolveApiOrigin(
  configuredOrigin: string | undefined,
  nodeEnv: string | undefined,
): string {
  if (configuredOrigin === undefined) {
    if (nodeEnv === "production") {
      throw new Error("TIEAT_API_ORIGIN is required for production builds.");
    }
    return DEVELOPMENT_API_ORIGIN;
  }

  if (!configuredOrigin.trim()) {
    throw new Error(INVALID_API_ORIGIN_MESSAGE);
  }

  if (configuredOrigin !== configuredOrigin.trim()) {
    throw new Error(INVALID_API_ORIGIN_MESSAGE);
  }

  let parsedOrigin: URL;
  try {
    parsedOrigin = new URL(configuredOrigin);
  } catch {
    throw new Error(INVALID_API_ORIGIN_MESSAGE);
  }

  if (
    !["http:", "https:"].includes(parsedOrigin.protocol) ||
    parsedOrigin.username ||
    parsedOrigin.password ||
    parsedOrigin.search ||
    parsedOrigin.hash ||
    parsedOrigin.pathname !== "/"
  ) {
    throw new Error(INVALID_API_ORIGIN_MESSAGE);
  }

  if (nodeEnv === "production" && ["localhost", "127.0.0.1", "::1", "[::1]"].includes(parsedOrigin.hostname)) {
    throw new Error(INVALID_API_ORIGIN_MESSAGE);
  }

  return parsedOrigin.origin;
}

const apiOrigin = resolveApiOrigin(process.env.TIEAT_API_ORIGIN, process.env.NODE_ENV);

const nextConfig: NextConfig = {
  agentRules: false,
  async rewrites() {
    return [
      {
        source: "/api/:path*",
        destination: `${apiOrigin}/api/:path*`,
      },
    ];
  },
  async headers() {
    return [
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
    ];
  },
};

export default nextConfig;
