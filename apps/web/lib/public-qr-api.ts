export type PublicQrPartner = {
  mealContractId: string;
  partnerDisplayName: string;
};

export type PublicMealUsageQrContext = {
  storeDisplayName: string;
  partners: PublicQrPartner[];
  qrExpiresAt: string;
  acceptingNewRequests: boolean;
};

export type PublicMealUsageStatus = "PENDING" | "CONFIRMED" | "REJECTED" | "CANCELLED";

export type PublicMealUsageRequest = {
  mealUsageId: string;
  status: PublicMealUsageStatus;
  amountMinor: number;
  createdAt: string;
};

export type PublicPendingMealUsage = PublicMealUsageRequest & {
  status: "PENDING";
};

export class PublicQrApiError extends Error {
  constructor(
    readonly status: number,
    readonly errorCode?: string,
  ) {
    super(errorCode ?? `Public QR API request failed with ${status}`);
  }
}

export class InvalidPublicQrApiResponseError extends PublicQrApiError {
  constructor() {
    super(0, "INVALID_PUBLIC_QR_API_RESPONSE");
  }
}

export class UnexpectedPublicQrCreationResponseError extends PublicQrApiError {
  constructor() {
    super(0, "UNEXPECTED_PUBLIC_QR_CREATION_RESPONSE");
  }
}

const API_PATH = "/api/v1/public/meal-usage-qr";

function contextPath(token: string): string {
  return `${API_PATH}/${encodeURIComponent(token)}`;
}

function requestPath(token: string, mealUsageId: string): string {
  return `${contextPath(token)}/meal-usages/${encodeURIComponent(mealUsageId)}`;
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

async function apiError(response: Response): Promise<PublicQrApiError> {
  const body: unknown = await response.json().catch(() => null);
  const errorCode = isRecord(body) && typeof body.errorCode === "string" ? body.errorCode : undefined;
  return new PublicQrApiError(response.status, errorCode);
}

function parsePartner(value: unknown): PublicQrPartner {
  if (!isRecord(value) || !isUuid(value.mealContractId) || !isNonEmptyString(value.partnerDisplayName)) {
    throw new InvalidPublicQrApiResponseError();
  }
  return { mealContractId: value.mealContractId, partnerDisplayName: value.partnerDisplayName };
}

function parseContext(value: unknown): PublicMealUsageQrContext {
  if (!isRecord(value)
    || !isNonEmptyString(value.storeDisplayName)
    || !Array.isArray(value.partners)
    || typeof value.acceptingNewRequests !== "boolean"
    || !isIsoInstant(value.qrExpiresAt)) {
    throw new InvalidPublicQrApiResponseError();
  }
  return {
    storeDisplayName: value.storeDisplayName,
    partners: value.partners.map(parsePartner),
    qrExpiresAt: value.qrExpiresAt,
    acceptingNewRequests: value.acceptingNewRequests,
  };
}

function parsePublicMealUsageRequest(value: unknown): PublicMealUsageRequest {
  if (!isRecord(value)
    || !isUuid(value.mealUsageId)
    || !["PENDING", "CONFIRMED", "REJECTED", "CANCELLED"].includes(String(value.status))
    || typeof value.amountMinor !== "number"
    || !Number.isSafeInteger(value.amountMinor)
    || value.amountMinor <= 0
    || !isIsoInstant(value.createdAt)) {
    throw new InvalidPublicQrApiResponseError();
  }
  return {
    mealUsageId: value.mealUsageId,
    status: value.status as PublicMealUsageStatus,
    amountMinor: value.amountMinor,
    createdAt: value.createdAt,
  };
}

function parsePendingUsage(value: unknown): PublicPendingMealUsage {
  const usage = parsePublicMealUsageRequest(value);
  if (usage.status !== "PENDING") {
    throw new UnexpectedPublicQrCreationResponseError();
  }
  return { ...usage, status: "PENDING" };
}

export async function getPublicMealUsageQrContext(token: string): Promise<PublicMealUsageQrContext> {
  const response = await fetch(contextPath(token), {
    cache: "no-store",
    credentials: "omit",
  });
  if (!response.ok) {
    throw await apiError(response);
  }
  return parseContext(await response.json() as unknown);
}

export async function createPublicMealUsage(
  token: string,
  idempotencyKey: string,
  publicRequestKey: string,
  mealContractId: string,
  customerName: string,
  amountMinor: number,
  publicClientKey: string,
): Promise<PublicPendingMealUsage> {
  const response = await fetch(`${contextPath(token)}/meal-usages`, {
    method: "POST",
    cache: "no-store",
    credentials: "omit",
    headers: {
      "Content-Type": "application/json",
      "Idempotency-Key": idempotencyKey,
      "Public-Request-Key": publicRequestKey,
      "Public-Client-Key": publicClientKey,
    },
    body: JSON.stringify({ mealContractId, customerName, amountMinor }),
  });
  if (response.status !== 201) {
    if (response.ok) {
      throw new UnexpectedPublicQrCreationResponseError();
    }
    throw await apiError(response);
  }
  return parsePendingUsage(await response.json() as unknown);
}

export async function getPublicMealUsageRequest(
  token: string,
  mealUsageId: string,
  idempotencyKey: string,
  publicRequestKey: string,
): Promise<PublicMealUsageRequest> {
  const response = await fetch(requestPath(token, mealUsageId), {
    cache: "no-store",
    credentials: "omit",
    headers: {
      "Idempotency-Key": idempotencyKey,
      "Public-Request-Key": publicRequestKey,
    },
  });
  if (!response.ok) {
    throw await apiError(response);
  }
  return parsePublicMealUsageRequest(await response.json() as unknown);
}

export async function cancelPublicMealUsage(
  token: string,
  mealUsageId: string,
  idempotencyKey: string,
  publicRequestKey: string,
): Promise<PublicMealUsageRequest> {
  const response = await fetch(`${requestPath(token, mealUsageId)}/cancellations`, {
    method: "POST",
    cache: "no-store",
    credentials: "omit",
    headers: {
      "Idempotency-Key": idempotencyKey,
      "Public-Request-Key": publicRequestKey,
    },
  });
  if (!response.ok) {
    throw await apiError(response);
  }
  return parsePublicMealUsageRequest(await response.json() as unknown);
}
