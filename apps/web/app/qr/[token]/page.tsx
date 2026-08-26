import type { Metadata } from "next";
import { MealUsageQrForm } from "./MealUsageQrForm";

export const metadata: Metadata = {
  referrer: "no-referrer",
};

export default async function QrMealUsagePage({ params }: { params: Promise<{ token: string }> }) {
  const { token } = await params;
  return <MealUsageQrForm token={token} />;
}
