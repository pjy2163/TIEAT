import { ApiError } from "@/lib/store-api";
import type { StorePartner } from "@/lib/store-partner-api";

export function paymentLabel(paymentType: StorePartner["paymentType"]): string {
  return paymentType === "POSTPAID" ? "후불" : "선불";
}

export function ledgerHref(mealContractId: string): string {
  return `/store/meal-usages/months?mealContractId=${encodeURIComponent(mealContractId)}`;
}

export function formatAmount(value: string): string {
  const digits = value.replace(/[^0-9]/g, "").replace(/^0+(?=\d)/, "").slice(0, 15);
  if (!digits) return "";
  return Number(digits).toLocaleString("en-US");
}

export function parsePositiveAmount(value: string): number | null {
  const digits = value.replace(/,/g, "").trim();
  if (!/^\d+$/.test(digits)) return null;
  const amount = Number(digits);
  return Number.isSafeInteger(amount) && amount > 0 ? amount : null;
}

export function apiErrorMessage(error: unknown, fallback: string): string {
  if (!(error instanceof ApiError)) return fallback;
  if (error.status === 409 && error.errorCode === "STORE_PARTNER_PAYMENT_TERM_STALE") {
    return "결제 유형이 이미 변경되었습니다. 상세를 닫고 목록을 새로 확인해 주세요.";
  }
  if (error.status === 409) return "미정산 금액이나 남은 선불 잔액이 있어 변경할 수 없습니다.";
  if (error.status === 400) return "입력한 값을 확인해 주세요.";
  return fallback;
}
