export type PendingMealUsage = {
  mealUsageId: string;
  status: "PENDING";
  entrySource: "STORE_TABLET" | "PARTNER_MOBILE";
  amountMinor: number;
  createdAt: string;
};

export type PendingMealUsagePage = {
  items: PendingMealUsage[];
  page: number;
  size: number;
  hasNext: boolean;
};

type CsrfToken = {
  token: string;
  headerName: string;
  parameterName: string;
};

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly errorCode?: string,
  ) {
    super(errorCode ?? `API request failed with ${status}`);
  }
}

export class InvalidApiResponseError extends ApiError {
  constructor() {
    super(0, "INVALID_API_RESPONSE");
  }
}

export class UnexpectedConfirmationResponseError extends ApiError {
  constructor() {
    super(0, "UNEXPECTED_CONFIRMATION_RESPONSE");
  }
}

const API_PATH = "/api/v1";
const PENDING_LIST_PATH = `${API_PATH}/meal-usages?status=PENDING&page=0&size=50`;

function confirmationPath(mealUsageId: string): string {
  return `${API_PATH}/meal-usages/${mealUsageId}/confirmations`;
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

function parseCsrfToken(value: unknown): CsrfToken {
  if (!isRecord(value)
    || !isNonEmptyString(value.token)
    || !isNonEmptyString(value.headerName)
    || !isNonEmptyString(value.parameterName)) {
    throw new InvalidApiResponseError();
  }
  return {
    token: value.token,
    headerName: value.headerName,
    parameterName: value.parameterName,
  };
}

function parsePendingMealUsage(value: unknown): PendingMealUsage {
  if (!isRecord(value)
    || !isUuid(value.mealUsageId)
    || value.status !== "PENDING"
    || (value.entrySource !== "STORE_TABLET" && value.entrySource !== "PARTNER_MOBILE")
    || typeof value.amountMinor !== "number"
    || !Number.isSafeInteger(value.amountMinor)
    || value.amountMinor <= 0
    || !isIsoInstant(value.createdAt)) {
    throw new InvalidApiResponseError();
  }
  return {
    mealUsageId: value.mealUsageId,
    status: value.status,
    entrySource: value.entrySource,
    amountMinor: value.amountMinor,
    createdAt: value.createdAt,
  };
}

function parsePendingMealUsagePage(value: unknown): PendingMealUsagePage {
  if (!isRecord(value)
    || !Array.isArray(value.items)
    || value.page !== 0
    || value.size !== 50
    || typeof value.hasNext !== "boolean") {
    throw new InvalidApiResponseError();
  }
  return {
    items: value.items.map(parsePendingMealUsage),
    page: value.page,
    size: value.size,
    hasNext: value.hasNext,
  };
}

export async function login(loginId: string, password: string): Promise<void> {
  const csrfResponse = await fetch(`${API_PATH}/csrf`, {
    cache: "no-store",
    credentials: "same-origin",
  });

  if (!csrfResponse.ok) {
    throw await apiError(csrfResponse);
  }

  const csrf = parseCsrfToken(await csrfResponse.json() as unknown);
  const body = new URLSearchParams({ loginId, password });
  const response = await fetch(`${API_PATH}/sessions`, {
    method: "POST",
    cache: "no-store",
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
      [csrf.headerName]: csrf.token,
    },
    body,
  });

  if (!response.ok) {
    throw await apiError(response);
  }
}

export async function getPendingMealUsages(): Promise<PendingMealUsagePage> {
  const response = await fetch(PENDING_LIST_PATH, {
    cache: "no-store",
    credentials: "same-origin",
  });

  if (!response.ok) {
    throw await apiError(response);
  }

  return parsePendingMealUsagePage(await response.json() as unknown);
}

export async function confirmMealUsage(mealUsageId: string, confirmerInitials: string): Promise<void> {
  const csrfResponse = await fetch(`${API_PATH}/csrf`, {
    cache: "no-store",
    credentials: "same-origin",
  });

  if (!csrfResponse.ok) {
    throw await apiError(csrfResponse);
  }

  const csrf = parseCsrfToken(await csrfResponse.json() as unknown);
  const response = await fetch(confirmationPath(mealUsageId), {
    method: "POST",
    cache: "no-store",
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/json",
      [csrf.headerName]: csrf.token,
    },
    body: JSON.stringify({ confirmerInitials }),
  });

  if (response.status !== 201) {
    if (response.ok) {
      throw new UnexpectedConfirmationResponseError();
    }
    throw await apiError(response);
  }
}
