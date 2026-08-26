import { ApiError, InvalidApiResponseError } from "./store-api";
import type { PartnerKind } from "./partner-kind";
export type { PartnerKind } from "./partner-kind";

export type StorePartner = {
  mealContractId: string;
  partnerOrganizationId?: string | null;
  partnerDisplayName: string;
  partnerKind: PartnerKind;
  paymentType: "POSTPAID" | "PREPAID_WITH_RECEIVABLE_OVERFLOW";
  qrSelectable: boolean;
  representativePhone: string | null;
  representativeEmail: string | null;
};

export type StorePartnerRegistration = {
  partnerName: string;
  partnerKind: PartnerKind;
  paymentType: StorePartner["paymentType"];
  initialPrepaidBalanceMinor: number;
  qrSelectable: boolean;
  representativePhone?: string | null;
  representativeEmail?: string | null;
};

export type StoreProfile = {
  loginId: string;
  storeDisplayName: string | null;
};

export type StorePartnerPaymentTermUpdate = {
  expectedPaymentType: StorePartner["paymentType"];
  paymentType: StorePartner["paymentType"];
  prepaidBalanceMinor?: number | null;
};

export type StoreArchivePinStatus = {
  configured: boolean;
};

export type StoreArchivePinSettings = {
  currentPin: string | null;
  accountPassword: string | null;
  newPin: string;
  newPinConfirmation: string;
};

type CsrfToken = {
  token: string;
  headerName: string;
};

const API_PATH = "/api/v1";
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === "string" && value.trim().length > 0;
}

function isUuid(value: unknown): value is string {
  return isNonEmptyString(value) && UUID_PATTERN.test(value);
}

function isNullableContact(value: unknown, maxLength: number): value is string | null {
  return value === null || (isNonEmptyString(value) && value.length <= maxLength);
}

async function apiError(response: Response): Promise<ApiError> {
  const body: unknown = await response.json().catch(() => null);
  const errorCode = isRecord(body) && typeof body.errorCode === "string" ? body.errorCode : undefined;
  return new ApiError(response.status, errorCode);
}

async function getCsrfToken(): Promise<CsrfToken> {
  const response = await fetch(`${API_PATH}/csrf`, {
    cache: "no-store",
    credentials: "same-origin",
  });
  if (!response.ok) throw await apiError(response);
  const body: unknown = await response.json();
  if (!isRecord(body) || !isNonEmptyString(body.token) || !isNonEmptyString(body.headerName)) {
    throw new InvalidApiResponseError();
  }
  return { token: body.token, headerName: body.headerName };
}

function parseStorePartner(value: unknown): StorePartner {
  if (!isRecord(value)) {
    throw new InvalidApiResponseError();
  }
  const fieldCount = Object.keys(value).length;
  const hasOrganizationId = "partnerOrganizationId" in value;
  const hasContacts = "representativePhone" in value || "representativeEmail" in value;
  const expectedFieldCount = hasOrganizationId
    ? hasContacts ? 8 : 6
    : hasContacts ? 7 : 5;
  if (fieldCount !== expectedFieldCount
    || !isUuid(value.mealContractId)
    || (hasOrganizationId && value.partnerOrganizationId !== null && !isUuid(value.partnerOrganizationId))
    || !isNonEmptyString(value.partnerDisplayName)
    || (value.partnerKind !== "INDIVIDUAL" && value.partnerKind !== "ORGANIZATION")
    || (value.paymentType !== "POSTPAID" && value.paymentType !== "PREPAID_WITH_RECEIVABLE_OVERFLOW")
    || typeof value.qrSelectable !== "boolean"
    || (hasContacts && (!hasOwnProperty(value, "representativePhone") || !hasOwnProperty(value, "representativeEmail")))
    || (hasContacts && !isNullableContact(value.representativePhone, 30))
    || (hasContacts && !isNullableContact(value.representativeEmail, 254))) {
    throw new InvalidApiResponseError();
  }
  const parsed: StorePartner = {
    mealContractId: value.mealContractId,
    partnerDisplayName: value.partnerDisplayName,
    partnerKind: value.partnerKind,
    paymentType: value.paymentType,
    qrSelectable: value.qrSelectable,
    representativePhone: hasContacts ? value.representativePhone as string | null : null,
    representativeEmail: hasContacts ? value.representativeEmail as string | null : null,
  };
  if (hasOrganizationId) {
    parsed.partnerOrganizationId = value.partnerOrganizationId === null ? null : value.partnerOrganizationId as string;
  }
  return parsed;
}

function hasOwnProperty(value: Record<string, unknown>, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(value, key);
}

function parseStoreProfile(value: unknown): StoreProfile {
  if (!isRecord(value)
    || Object.keys(value).length !== 2
    || !isNonEmptyString(value.loginId)
    || (value.storeDisplayName !== null && !isNonEmptyString(value.storeDisplayName))) {
    throw new InvalidApiResponseError();
  }
  return { loginId: value.loginId, storeDisplayName: value.storeDisplayName };
}

export async function getStorePartners(): Promise<StorePartner[]> {
  const response = await fetch(`${API_PATH}/store-partners`, {
    cache: "no-store",
    credentials: "same-origin",
  });
  if (!response.ok) throw await apiError(response);
  const body: unknown = await response.json();
  if (!Array.isArray(body)) throw new InvalidApiResponseError();
  return body.map(parseStorePartner);
}

export async function createStorePartner(
  request: StorePartnerRegistration,
  idempotencyKey: string,
): Promise<StorePartner> {
  if (!isUuid(idempotencyKey)) throw new InvalidApiResponseError();
  const csrf = await getCsrfToken();
  const response = await fetch(`${API_PATH}/store-partners`, {
    method: "POST",
    cache: "no-store",
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/json",
      "Idempotency-Key": idempotencyKey,
      [csrf.headerName]: csrf.token,
    },
    body: JSON.stringify(request),
  });
  if (response.status !== 201) {
    if (response.ok) throw new InvalidApiResponseError();
    throw await apiError(response);
  }
  return parseStorePartner(await response.json());
}

export async function updateStorePartnerPaymentType(
  mealContractId: string,
  request: StorePartnerPaymentTermUpdate,
): Promise<StorePartner> {
  if (!isUuid(mealContractId)) throw new InvalidApiResponseError();
  const csrf = await getCsrfToken();
  const response = await fetch(`${API_PATH}/store-partners/${encodeURIComponent(mealContractId)}/payment-terms`, {
    method: "PATCH",
    cache: "no-store",
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/json",
      [csrf.headerName]: csrf.token,
    },
    body: JSON.stringify(request),
  });
  if (!response.ok) throw await apiError(response);
  return parseStorePartner(await response.json());
}

export async function getArchivePinStatus(): Promise<StoreArchivePinStatus> {
  const response = await fetch(`${API_PATH}/store-archive-pin`, {
    cache: "no-store",
    credentials: "same-origin",
  });
  if (!response.ok) throw await apiError(response);
  const body: unknown = await response.json();
  if (!isRecord(body) || Object.keys(body).length !== 1 || typeof body.configured !== "boolean") {
    throw new InvalidApiResponseError();
  }
  return { configured: body.configured };
}

export async function setArchivePin(request: StoreArchivePinSettings): Promise<StoreArchivePinStatus> {
  const csrf = await getCsrfToken();
  const response = await fetch(`${API_PATH}/store-archive-pin`, {
    method: "PUT",
    cache: "no-store",
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/json",
      [csrf.headerName]: csrf.token,
    },
    body: JSON.stringify(request),
  });
  if (!response.ok) throw await apiError(response);
  const body: unknown = await response.json();
  if (!isRecord(body) || Object.keys(body).length !== 1 || body.configured !== true) {
    throw new InvalidApiResponseError();
  }
  return { configured: true };
}

export async function archiveStorePartner(mealContractId: string, pin: string): Promise<void> {
  if (!isUuid(mealContractId)) throw new InvalidApiResponseError();
  const csrf = await getCsrfToken();
  const response = await fetch(`${API_PATH}/store-partners/${encodeURIComponent(mealContractId)}/archive`, {
    method: "POST",
    cache: "no-store",
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/json",
      [csrf.headerName]: csrf.token,
    },
    body: JSON.stringify({ pin }),
  });
  if (response.status !== 204) {
    if (response.ok) throw new InvalidApiResponseError();
    throw await apiError(response);
  }
}

export async function getStoreProfile(): Promise<StoreProfile> {
  const response = await fetch(`${API_PATH}/store-profile`, {
    cache: "no-store",
    credentials: "same-origin",
  });
  if (!response.ok) throw await apiError(response);
  return parseStoreProfile(await response.json());
}
