import type { Metadata } from "next";
import { MealUsageQrForm } from "./MealUsageQrForm";

export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  referrer: "no-referrer",
  robots: {
    index: false,
    follow: false,
    noarchive: true,
    nocache: true,
  },
};

export default async function QrMealUsagePage({ params }: { params: Promise<{ token: string }> }) {
  const { token } = await params;
  return <MealUsageQrForm token={token} />;
}
