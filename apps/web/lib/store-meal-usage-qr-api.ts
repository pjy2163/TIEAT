import { ApiError, InvalidApiResponseError } from "./store-api";

export type StoreMealUsageQrStatus = "AVAILABLE" | "NOT_AVAILABLE" | "EXPIRED" | "REISSUE_REQUIRED";

export type StoreMealUsageQrView = {
  status: StoreMealUsageQrStatus;
  publicPath: string | null;
  issuedAt: string | null;
  expiresAt: string | null;
  acceptingNewRequests: boolean;
};

type CsrfToken = {
  token: string;
  headerName: string;
  parameterName: string;
};

const API_PATH = "/api/v1/store-meal-usage-qr";
const PUBLIC_PATH_PATTERN = /^\/qr\/[A-Za-z0-9_-]{43}$/;
const ISO_INSTANT_PATTERN = /^(\d{4})-(\d{2})-(\d{2})T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?(?:Z|[+-]\d{2}:\d{2})$/;

async function apiError(response: Response): Promise<ApiError> {
  const body: unknown = await response.json().catch(() => null);
  const errorCode = isRecord(body) && typeof body.errorCode === "string" ? body.errorCode : undefined;
  return new ApiError(response.status, errorCode);
}

function parseCsrfToken(value: unknown): CsrfToken {
  if (!isRecord(value)
    || typeof value.token !== "string"
    || value.token.trim().length === 0
    || typeof value.headerName !== "string"
    || value.headerName.trim().length === 0
    || typeof value.parameterName !== "string"
    || value.parameterName.trim().length === 0) {
    throw new InvalidApiResponseError();
  }
  return {
    token: value.token,
    headerName: value.headerName,
    parameterName: value.parameterName,
  };
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
  const fields = ["status", "publicPath", "issuedAt", "expiresAt", "acceptingNewRequests"];
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
  const acceptingNewRequests = value.acceptingNewRequests;
  if ((publicPath !== null && (typeof publicPath !== "string" || !PUBLIC_PATH_PATTERN.test(publicPath)))
    || (issuedAt !== null && !isIsoInstant(issuedAt))
    || (expiresAt !== null && !isIsoInstant(expiresAt))
    || typeof acceptingNewRequests !== "boolean") {
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
    acceptingNewRequests: acceptingNewRequests as boolean,
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

export async function renewStoreMealUsageQr(): Promise<StoreMealUsageQrView> {
  const csrfResponse = await fetch("/api/v1/csrf", {
    cache: "no-store",
    credentials: "same-origin",
  });
  if (!csrfResponse.ok) throw await apiError(csrfResponse);
  const csrf = parseCsrfToken(await csrfResponse.json() as unknown);

  const response = await fetch(`${API_PATH}/renewals`, {
    method: "POST",
    cache: "no-store",
    credentials: "same-origin",
    headers: {
      [csrf.headerName]: csrf.token,
    },
  });
  if (!response.ok) throw await apiError(response);
  return parseView(await response.json() as unknown);
}

async function changeStoreMealUsageQrPause(method: "POST" | "DELETE"): Promise<StoreMealUsageQrView> {
  const csrfResponse = await fetch("/api/v1/csrf", { cache: "no-store", credentials: "same-origin" });
  if (!csrfResponse.ok) throw await apiError(csrfResponse);
  const csrf = parseCsrfToken(await csrfResponse.json() as unknown);
  const response = await fetch(`${API_PATH}/request-pauses`, {
    method,
    cache: "no-store",
    credentials: "same-origin",
    headers: { [csrf.headerName]: csrf.token },
  });
  if (!response.ok) throw await apiError(response);
  return parseView(await response.json() as unknown);
}

export function pauseStoreMealUsageQr(): Promise<StoreMealUsageQrView> {
  return changeStoreMealUsageQrPause("POST");
}

export function resumeStoreMealUsageQr(): Promise<StoreMealUsageQrView> {
  return changeStoreMealUsageQrPause("DELETE");
}
