import type { PartnerKind } from "./partner-kind";

export type PendingMealUsage = {
  mealUsageId: string;
  status: "PENDING";
  entrySource: "STORE_TABLET" | "PARTNER_MOBILE";
  partnerDisplayName: string | null;
  customerName: string | null;
  amountMinor: number;
  createdAt: string;
};

export type PendingMealUsagePage = {
  items: PendingMealUsage[];
  page: number;
  size: number;
  hasNext: boolean;
};

export type StorePlaceSearchResult = {
  placeId: string;
  storeDisplayName: string;
  address: string | null;
  category: string | null;
};

export type StoreOnboardingStatus = {
  onboardingStatus: "PARTNER_REQUIRED" | "COMPLETE";
  legacy: boolean;
};

export type FirstPartnerRegistration = {
  partnerName: string;
  partnerKind: PartnerKind;
  paymentType: "POSTPAID" | "PREPAID_WITH_RECEIVABLE_OVERFLOW";
  initialPrepaidBalanceMinor: number;
  qrSelectable: boolean;
};

export type FirstPartnerRegistrationResult = StoreOnboardingStatus & {
  created: boolean;
  partnerDisplayName: string | null;
  partnerKind: PartnerKind | null;
  paymentType: "POSTPAID" | "PREPAID_WITH_RECEIVABLE_OVERFLOW" | null;
  mealContractId: string | null;
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

export class UnexpectedRejectionResponseError extends ApiError {
  constructor() {
    super(0, "UNEXPECTED_REJECTION_RESPONSE");
  }
}

export class UnexpectedSignupResponseError extends ApiError {
  constructor() {
    super(0, "UNEXPECTED_SIGNUP_RESPONSE");
  }
}

export class UnexpectedPartnerRegistrationResponseError extends ApiError {
  constructor() {
    super(0, "UNEXPECTED_PARTNER_REGISTRATION_RESPONSE");
  }
}

const API_PATH = "/api/v1";
const PENDING_LIST_PATH = `${API_PATH}/meal-usages?status=PENDING&page=0&size=50`;

function confirmationPath(mealUsageId: string): string {
  return `${API_PATH}/meal-usages/${mealUsageId}/confirmations`;
}

function rejectionPath(mealUsageId: string): string {
  return `${API_PATH}/meal-usages/${mealUsageId}/rejections`;
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

function parseStorePlaceSearchResult(value: unknown): StorePlaceSearchResult {
  if (!isRecord(value)
    || !isNonEmptyString(value.placeId)
    || !isNonEmptyString(value.storeDisplayName)
    || (value.address !== null && !isNonEmptyString(value.address))
    || (value.category !== null && !isNonEmptyString(value.category))) {
    throw new InvalidApiResponseError();
  }
  return {
    placeId: value.placeId,
    storeDisplayName: value.storeDisplayName,
    address: value.address,
    category: value.category,
  };
}

function parseStorePlaceSearch(value: unknown): StorePlaceSearchResult[] {
  if (!isRecord(value) || value.source !== "KAKAO" || !Array.isArray(value.items) || value.items.length > 10) {
    throw new InvalidApiResponseError();
  }
  return value.items.map(parseStorePlaceSearchResult);
}

function parseOnboardingStatus(value: unknown): StoreOnboardingStatus {
  if (!isRecord(value)
    || (value.onboardingStatus !== "PARTNER_REQUIRED" && value.onboardingStatus !== "COMPLETE")
    || typeof value.legacy !== "boolean") {
    throw new InvalidApiResponseError();
  }
  return {
    onboardingStatus: value.onboardingStatus,
    legacy: value.legacy,
  };
}

function parseSignupResult(value: unknown): StoreOnboardingStatus {
  if (!isRecord(value) || value.onboardingStatus !== "PARTNER_REQUIRED") {
    throw new InvalidApiResponseError();
  }
  return { onboardingStatus: value.onboardingStatus, legacy: false };
}

function parseFirstPartnerRegistrationResult(value: unknown): FirstPartnerRegistrationResult {
  const status = parseOnboardingStatus(value);
  if (!isRecord(value)
    || typeof value.created !== "boolean"
    || (value.partnerDisplayName !== null && !isNonEmptyString(value.partnerDisplayName))
    || (value.partnerKind !== null
      && value.partnerKind !== "INDIVIDUAL"
      && value.partnerKind !== "ORGANIZATION")
    || (value.mealContractId !== null && !isUuid(value.mealContractId))
    || (value.paymentType !== null
      && value.paymentType !== "POSTPAID"
      && value.paymentType !== "PREPAID_WITH_RECEIVABLE_OVERFLOW")) {
    throw new InvalidApiResponseError();
  }
  return {
    ...status,
    created: value.created,
    partnerDisplayName: value.partnerDisplayName,
    partnerKind: value.partnerKind,
    paymentType: value.paymentType,
    mealContractId: value.mealContractId,
  };
}

function parsePendingMealUsage(value: unknown): PendingMealUsage {
  if (!isRecord(value)
    || !isUuid(value.mealUsageId)
    || value.status !== "PENDING"
    || (value.entrySource !== "STORE_TABLET" && value.entrySource !== "PARTNER_MOBILE")
    || (value.partnerDisplayName !== null && !isNonEmptyString(value.partnerDisplayName))
    || (value.customerName !== null && !isNonEmptyString(value.customerName))
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
    partnerDisplayName: value.partnerDisplayName,
    customerName: value.customerName,
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

async function getCsrfToken(): Promise<CsrfToken> {
  const csrfResponse = await fetch(`${API_PATH}/csrf`, {
    cache: "no-store",
    credentials: "same-origin",
  });

  if (!csrfResponse.ok) {
    throw await apiError(csrfResponse);
  }

  return parseCsrfToken(await csrfResponse.json() as unknown);
}

export async function login(loginId: string, password: string, remember = false): Promise<void> {
  const csrf = await getCsrfToken();
  const body = new URLSearchParams({ loginId, password });
  if (remember) body.set("rememberLogin", "true");
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

export async function reauthenticateStoreSession(password: string): Promise<void> {
  const csrf = await getCsrfToken();
  const response = await fetch(`${API_PATH}/session-reauthentications`, {
    method: "POST",
    cache: "no-store",
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/json",
      [csrf.headerName]: csrf.token,
    },
    body: JSON.stringify({ password }),
  });
  if (response.status !== 204) {
    if (response.ok) throw new InvalidApiResponseError();
    throw await apiError(response);
  }
}

export async function searchStorePlaces(request: {
  inviteCode: string;
  query: string;
}): Promise<StorePlaceSearchResult[]> {
  const csrf = await getCsrfToken();
  const response = await fetch(`${API_PATH}/store-place-searches`, {
    method: "POST",
    cache: "no-store",
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/json",
      [csrf.headerName]: csrf.token,
    },
    body: JSON.stringify(request),
  });
  if (!response.ok) {
    throw await apiError(response);
  }
  return parseStorePlaceSearch(await response.json() as unknown);
}

export async function signUpStoreAccount(request: {
  inviteCode: string;
  loginId: string;
  password: string;
  manualStoreName: string;
}): Promise<StoreOnboardingStatus> {
  const csrf = await getCsrfToken();
  const response = await fetch(`${API_PATH}/store-signups`, {
    method: "POST",
    cache: "no-store",
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/json",
      [csrf.headerName]: csrf.token,
    },
    body: JSON.stringify(request),
  });
  if (response.status !== 201) {
    if (response.ok) {
      throw new UnexpectedSignupResponseError();
    }
    throw await apiError(response);
  }
  return parseSignupResult(await response.json() as unknown);
}

export async function getStoreOnboardingStatus(): Promise<StoreOnboardingStatus> {
  const response = await fetch(`${API_PATH}/store-onboarding`, {
    cache: "no-store",
    credentials: "same-origin",
  });
  if (!response.ok) {
    throw await apiError(response);
  }
  return parseOnboardingStatus(await response.json() as unknown);
}

export async function registerFirstPartner(
  request: FirstPartnerRegistration,
): Promise<FirstPartnerRegistrationResult> {
  const csrf = await getCsrfToken();
  const response = await fetch(`${API_PATH}/store-onboarding/partners`, {
    method: "POST",
    cache: "no-store",
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/json",
      [csrf.headerName]: csrf.token,
    },
    body: JSON.stringify(request),
  });
  if (response.status !== 201 && response.status !== 200) {
    throw await apiError(response);
  }
  try {
    return parseFirstPartnerRegistrationResult(await response.json() as unknown);
  } catch (error) {
    if (error instanceof InvalidApiResponseError) {
      throw new UnexpectedPartnerRegistrationResponseError();
    }
    throw error;
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
  const csrf = await getCsrfToken();
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

export async function rejectMealUsage(mealUsageId: string): Promise<void> {
  const csrf = await getCsrfToken();
  const response = await fetch(rejectionPath(mealUsageId), {
    method: "POST",
    cache: "no-store",
    credentials: "same-origin",
    headers: {
      [csrf.headerName]: csrf.token,
    },
  });

  if (response.status !== 201) {
    if (response.ok) {
      throw new UnexpectedRejectionResponseError();
    }
    throw await apiError(response);
  }
}
