import { ApiError, InvalidApiResponseError } from "./store-api";

export type StoreMealUsageQrStatus = "AVAILABLE" | "NOT_AVAILABLE" | "EXPIRED" | "REISSUE_REQUIRED";

export type StoreMealUsageQrView = {
  status: StoreMealUsageQrStatus;
  publicPath: string | null;
  issuedAt: string | null;
  expiresAt: string | null;
};

const API_PATH = "/api/v1/store-meal-usage-qr";
const PUBLIC_PATH_PATTERN = /^\/qr\/[A-Za-z0-9_-]{43}$/;
const ISO_INSTANT_PATTERN = /^(\d{4})-(\d{2})-(\d{2})T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?(?:Z|[+-]\d{2}:\d{2})$/;

async function apiError(response: Response): Promise<ApiError> {
  const body: unknown = await response.json().catch(() => null);
  const errorCode = isRecord(body) && typeof body.errorCode === "string" ? body.errorCode : undefined;
  return new ApiError(response.status, errorCode);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isIsoInstant(value: unknown): value is string {
  if (typeof value !== "string" || !ISO_INSTANT_PATTERN.test(value) || Number.isNaN(Date.parse(value))) return false;
  const match = ISO_INSTANT_PATTERN.exec(value);
  if (!match) return false;
  const [year, month, day] = match.slice(1, 4).map(Number);
  const calendarDate = new Date(Date.UTC(year, month - 1, day));
  return calendarDate.getUTCFullYear() === year
    && calendarDate.getUTCMonth() === month - 1
    && calendarDate.getUTCDate() === day;
}

function hasExactlyFields(value: Record<string, unknown>): boolean {
  const fields = ["status", "publicPath", "issuedAt", "expiresAt"];
  return Object.keys(value).length === fields.length && fields.every((field) => field in value);
}

function parseView(value: unknown): StoreMealUsageQrView {
  if (!isRecord(value) || !hasExactlyFields(value)) throw new InvalidApiResponseError();

  const status = value.status;
  if (status !== "AVAILABLE" && status !== "NOT_AVAILABLE" && status !== "EXPIRED" && status !== "REISSUE_REQUIRED") {
    throw new InvalidApiResponseError();
  }
  const publicPath = value.publicPath;
  const issuedAt = value.issuedAt;
  const expiresAt = value.expiresAt;
  if ((publicPath !== null && (typeof publicPath !== "string" || !PUBLIC_PATH_PATTERN.test(publicPath)))
    || (issuedAt !== null && !isIsoInstant(issuedAt))
    || (expiresAt !== null && !isIsoInstant(expiresAt))) {
    throw new InvalidApiResponseError();
  }

  const needsDates = status !== "NOT_AVAILABLE";
  const needsPath = status === "AVAILABLE";
  if ((needsDates && (issuedAt === null || expiresAt === null))
    || (!needsDates && (issuedAt !== null || expiresAt !== null))
    || (needsPath && publicPath === null)
    || (!needsPath && publicPath !== null)) {
    throw new InvalidApiResponseError();
  }

  return {
    status,
    publicPath,
    issuedAt,
    expiresAt,
  };
}

export async function getStoreMealUsageQr(): Promise<StoreMealUsageQrView> {
  const response = await fetch(API_PATH, {
    cache: "no-store",
    credentials: "same-origin",
  });
  if (!response.ok) throw await apiError(response);
  return parseView(await response.json() as unknown);
}
