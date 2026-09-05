import type { MetadataRoute } from "next";

export default function manifest(): MetadataRoute.Manifest {
  return {
    name: "TIEAT 식대 장부",
    short_name: "TIEAT 장부",
    description: "매장 식대 요청 확인과 월별 장부 관리",
    start_url: "/store/meal-usages",
    scope: "/store",
    display: "standalone",
    background_color: "#ffffff",
    theme_color: "#315efb",
    icons: [
      { src: "/pwa-icon-192.png", sizes: "192x192", type: "image/png" },
      { src: "/pwa-icon-512.png", sizes: "512x512", type: "image/png" },
    ],
  };
}
