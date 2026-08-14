import { ApiError, InvalidApiResponseError } from "./store-api";

export type MonthlyMealUsageStatus = "CONFIRMED";

export type MonthlyMealUsage = {
  id: string;
  status: MonthlyMealUsageStatus;
  partnerDisplayName: string | null;
  amountMinor: number;
  createdAt: string;
  confirmedStaffInitials: string;
};

export type MonthlyMealUsagePage = {
  month: string;
  fromMonth: string;
  toMonth: string;
  timeZone: "Asia/Seoul";
  items: MonthlyMealUsage[];
  page: number;
  size: number;
  hasNext: boolean;
  totalAmountMinor: number;
};

const API_PATH = "/api/v1";
const YEAR_MONTH_PATTERN = /^\d{4}-(0[1-9]|1[0-2])$/;

function monthlyLedgerPath(fromMonth: string, toMonth: string, page: number, size: number): string {
  const toQuery = fromMonth === toMonth ? "" : `&to=${encodeURIComponent(toMonth)}`;
  return `${API_PATH}/meal-usages/months/${encodeURIComponent(fromMonth)}?page=${page}&size=${size}${toQuery}`;
}

async function apiError(response: Response): Promise<ApiError> {
  const body: unknown = await response.json().catch(() => null);
  const errorCode = isRecord(body) && typeof body.errorCode === "string" ? body.errorCode : undefined;
  return new ApiError(response.status, errorCode);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === "string" && value.trim().length > 0;
}

function isUuid(value: unknown): value is string {
  return isNonEmptyString(value)
    && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
}

function isIsoInstant(value: unknown): value is string {
  if (!isNonEmptyString(value)) return false;
  const match = /^(\d{4})-(\d{2})-(\d{2})T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?(?:Z|[+-]\d{2}:\d{2})$/.exec(value);
  if (!match || Number.isNaN(Date.parse(value))) return false;

  const [year, month, day] = match.slice(1).map(Number);
  const calendarDate = new Date(Date.UTC(year, month - 1, day));
  return calendarDate.getUTCFullYear() === year
    && calendarDate.getUTCMonth() === month - 1
    && calendarDate.getUTCDate() === day;
}

function hasExactlyFields(value: Record<string, unknown>, expectedFields: readonly string[]): boolean {
  const actualFields = Object.keys(value);
  return actualFields.length === expectedFields.length && expectedFields.every((field) => field in value);
}

function parseMonthlyMealUsage(value: unknown): MonthlyMealUsage {
  if (!isRecord(value)
    || !hasExactlyFields(value, ["id", "status", "partnerDisplayName", "amountMinor", "createdAt", "confirmedStaffInitials"])
    || !isUuid(value.id)
    || value.status !== "CONFIRMED"
    || (value.partnerDisplayName !== null && !isNonEmptyString(value.partnerDisplayName))
    || typeof value.amountMinor !== "number"
    || !Number.isSafeInteger(value.amountMinor)
    || value.amountMinor <= 0
    || !isIsoInstant(value.createdAt)
    || !isNonEmptyString(value.confirmedStaffInitials)) {
    throw new InvalidApiResponseError();
  }
  return {
    id: value.id,
    status: "CONFIRMED",
    partnerDisplayName: value.partnerDisplayName,
    amountMinor: value.amountMinor,
    createdAt: value.createdAt,
    confirmedStaffInitials: value.confirmedStaffInitials,
  };
}

function parseMonthlyMealUsagePage(
  value: unknown,
  expectedFromMonth: string,
  expectedToMonth: string,
  expectedPage: number,
  expectedSize: number,
): MonthlyMealUsagePage {
  if (!isRecord(value)
    || value.month !== expectedFromMonth
    || value.fromMonth !== expectedFromMonth
    || value.toMonth !== expectedToMonth
    || value.timeZone !== "Asia/Seoul"
    || !Array.isArray(value.items)
    || value.page !== expectedPage
    || value.size !== expectedSize
    || typeof value.hasNext !== "boolean"
    || typeof value.totalAmountMinor !== "number"
    || !Number.isSafeInteger(value.totalAmountMinor)
    || value.totalAmountMinor < 0) {
    throw new InvalidApiResponseError();
  }
  return {
    month: value.month,
    fromMonth: value.fromMonth,
    toMonth: value.toMonth,
    timeZone: value.timeZone,
    items: value.items.map(parseMonthlyMealUsage),
    page: value.page,
    size: value.size,
    hasNext: value.hasNext,
    totalAmountMinor: value.totalAmountMinor,
  };
}

export function getMonthlyMealUsages(
  fromMonth: string,
  toMonth: string,
  page: number,
  size: number,
): Promise<MonthlyMealUsagePage>;
export function getMonthlyMealUsages(
  month: string,
  page: number,
  size: number,
  toMonth?: string,
): Promise<MonthlyMealUsagePage>;
export async function getMonthlyMealUsages(
  fromMonth: string,
  toMonthOrPage: string | number,
  pageOrSize: number,
  sizeOrToMonth?: number | string,
): Promise<MonthlyMealUsagePage> {
  let toMonth: string;
  let page: number;
  let size: number;
  if (typeof toMonthOrPage === "string") {
    toMonth = toMonthOrPage;
    page = pageOrSize;
    size = typeof sizeOrToMonth === "number" ? sizeOrToMonth : Number.NaN;
  } else {
    toMonth = typeof sizeOrToMonth === "string" ? sizeOrToMonth : fromMonth;
    page = toMonthOrPage;
    size = pageOrSize;
  }
  if (!YEAR_MONTH_PATTERN.test(fromMonth)
    || !YEAR_MONTH_PATTERN.test(toMonth)
    || !Number.isInteger(page)
    || page < 0
    || !Number.isInteger(size)
    || size < 1
    || size > 100) {
    throw new InvalidApiResponseError();
  }
  const response = await fetch(monthlyLedgerPath(fromMonth, toMonth, page, size), {
    cache: "no-store",
    credentials: "same-origin",
  });

  if (!response.ok) {
    throw await apiError(response);
  }

  return parseMonthlyMealUsagePage(await response.json() as unknown, fromMonth, toMonth, page, size);
}
