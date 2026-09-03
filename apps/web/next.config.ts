import type { NextConfig } from "next";

const DEVELOPMENT_API_ORIGIN = "http://localhost:8080";
const INVALID_API_ORIGIN_MESSAGE =
  "TIEAT_API_ORIGIN must be an absolute http(s) origin without credentials, query, fragment, or a non-root path.";
const CONTENT_SECURITY_POLICY =
  "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; frame-src 'self' blob:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'";

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

export function buildSecurityHeaders(nodeEnv: string | undefined) {
  const globalHeaders = [
    {
      key: nodeEnv === "production" ? "Content-Security-Policy" : "Content-Security-Policy-Report-Only",
      value: CONTENT_SECURITY_POLICY,
    },
    ...(nodeEnv === "production"
      ? [{ key: "Strict-Transport-Security", value: "max-age=31536000" }]
      : []),
    { key: "Referrer-Policy", value: "no-referrer" },
    { key: "X-Content-Type-Options", value: "nosniff" },
    { key: "X-Frame-Options", value: "DENY" },
    {
      key: "Permissions-Policy",
      value: "camera=(), microphone=(), geolocation=()",
    },
  ];

  return [
    { source: "/:path*", headers: globalHeaders },
    {
      source: "/qr/:token",
      headers: [
        { key: "Cache-Control", value: "no-store, max-age=0" },
        { key: "X-Robots-Tag", value: "noindex, nofollow, noarchive" },
      ],
    },
  ];
}

const nextConfig: NextConfig = {
  agentRules: false,
  output: "standalone",
  async rewrites() {
    return [
      {
        source: "/api/:path*",
        destination: `${apiOrigin}/api/:path*`,
      },
    ];
  },
  async headers() {
    return buildSecurityHeaders(process.env.NODE_ENV);
  },
};

export default nextConfig;
