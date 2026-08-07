import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "TIEAT 매장 태블릿",
  description: "매장 확인 대기 식대 사용 내역",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="ko">
      <body>{children}</body>
    </html>
  );
}
