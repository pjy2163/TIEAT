import { MealUsageQrForm } from "./MealUsageQrForm";

export default async function QrMealUsagePage({ params }: { params: Promise<{ token: string }> }) {
  const { token } = await params;
  return <MealUsageQrForm token={token} />;
}
