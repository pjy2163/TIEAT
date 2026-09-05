import type { Metadata, Viewport } from "next";
import "./globals.css";

export const metadata: Metadata = {
  metadataBase: new URL("https://tieat.paranglabs.com"),
  title: "TIEAT 식대 장부",
  description: "QR 입력부터 매장 확인, 결제할 금액 확인까지 한 큐에 보는 TIEAT 식대 장부",
  openGraph: {
    title: "TIEAT 식대 장부",
    description: "QR 입력부터 매장 확인, 결제할 금액 확인까지 한 큐에 보는 TIEAT 식대 장부",
    siteName: "TIEAT 식대 장부",
    locale: "ko_KR",
    type: "website",
    images: [{ url: "/og/tieat-og.png", width: 1200, height: 630, alt: "TIEAT 식대 장부 흐름" }],
  },
  twitter: {
    card: "summary_large_image",
    title: "TIEAT 식대 장부",
    description: "QR 입력부터 매장 확인, 결제할 금액 확인까지 한 큐에 보는 TIEAT 식대 장부",
    images: ["/og/tieat-og.png"],
  },
  manifest: "/manifest.webmanifest",
  icons: {
    icon: "/icon.svg",
    shortcut: "/icon.svg",
    apple: "/apple-icon.png",
  },
};

export const viewport: Viewport = {
  themeColor: "#315efb",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="ko">
      <body>{children}</body>
    </html>
  );
}
