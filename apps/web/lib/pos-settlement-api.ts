import { ApiError, InvalidApiResponseError } from "./store-api";

export type OutstandingReceivable = {
  mealUsageId: string;
  mealContractId: string;
  partnerDisplayName: string | null;
  confirmedAt: string;
  receivableCreatedMinor: number;
};

export type OutstandingReceivableOverview = {
  items: OutstandingReceivable[];
  partners: PartnerReceivableSummary[];
};

export type PartnerReceivableSummary = {
  mealContractId: string;
  partnerDisplayName: string | null;
  previousPosBusinessDate: string | null;
  periodConfirmedUsageTotalMinor: number;
  periodPrepaidAppliedTotalMinor: number;
  outstandingReceivableCount: number;
  outstandingReceivableTotalMinor: number;
};

export type PosSettlement = {
  /** Opaque request-only ID; never render this value to staff. */
  posSettlementId?: string;
  posBusinessDate: string;
  submittedTotalMinor: number;
  recordedAt: string;
  allocations: PosSettlementAllocation[];
};

export type PosSettlementHistoryPage = {
  items: PosSettlement[];
  page: number;
  size: number;
  hasNext: boolean;
};

export type PosSettlementAllocation = {
  partnerDisplayName: string | null;
  confirmedAt: string;
  receivableAmountMinor: number;
};

export type PosSettlementRequest = {
  mealContractId: string;
  posBusinessDate: string;
  submittedTotalMinor: number;
  mealUsageIds: string[];
};

export type PosSettlementSelectionSeed = {
  version: 1;
  mealUsageIds: string[];
  /**
   * These values are carried only as a short-lived navigation hint. The
   * settlement form must derive the contract and amount again from the
   * authoritative receivables overview before recording anything.
   */
  mealContractId: string | null;
  amountMinor: number | null;
  createdAt: number;
};

export class UnexpectedPosSettlementResponseError extends ApiError {
  constructor() {
    super(0, "UNEXPECTED_POS_SETTLEMENT_RESPONSE");
  }
}

type CsrfToken = {
  token: string;
  headerName: string;
  parameterName: string;
};

const API_PATH = "/api/v1";
const RECEIVABLES_PATH = `${API_PATH}/pos-settlements/receivables`;
const SETTLEMENTS_PATH = `${API_PATH}/pos-settlements`;

const RECENT_SETTLEMENTS_PAGE = 0;
const RECENT_SETTLEMENTS_SIZE = 20;
export const POS_SETTLEMENT_SELECTION_SEED_STORAGE_KEY = "tieat.pos-settlement-selection.v1";
const POS_SETTLEMENT_SELECTION_SEED_TTL_MS = 5 * 60 * 1000;

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

function isValidSelectionSeedUsageIds(value: unknown): value is string[] {
  if (!Array.isArray(value) || value.length === 0 || value.length > 100) return false;
  const ids = value.filter(isUuid);
  return ids.length === value.length && new Set(ids).size === ids.length;
}

/**
 * Stores a same-tab navigation hint for the monthly ledger handoff. A
 * sessionStorage failure is reported to the caller so it can keep the user
 * on the monthly page instead of navigating without the selected rows.
 */
export function writePosSettlementSelectionSeed(seed: {
  mealUsageIds: string[];
  mealContractId: string | null;
  amountMinor: number | null;
}): boolean {
  if (!isValidSelectionSeedUsageIds(seed.mealUsageIds)) return false;
  try {
    window.sessionStorage.setItem(POS_SETTLEMENT_SELECTION_SEED_STORAGE_KEY, JSON.stringify({
      version: 1,
      mealUsageIds: seed.mealUsageIds,
      mealContractId: seed.mealContractId,
      amountMinor: seed.amountMinor,
      createdAt: Date.now(),
    }));
    return true;
  } catch {
    return false;
  }
}

export function discardPosSettlementSelectionSeed(): void {
  try {
    window.sessionStorage.removeItem(POS_SETTLEMENT_SELECTION_SEED_STORAGE_KEY);
  } catch {
    // Storage can be unavailable in privacy-restricted contexts.
  }
}

/**
 * Reads and consumes the one-time monthly handoff. Only usage IDs and expiry
 * are trusted here; amount and contract fields are intentionally treated as
 * untrusted hints by the consumer.
 */
export function takePosSettlementSelectionSeed(): PosSettlementSelectionSeed | null {
  let raw: string | null = null;
  try {
    raw = window.sessionStorage.getItem(POS_SETTLEMENT_SELECTION_SEED_STORAGE_KEY);
    discardPosSettlementSelectionSeed();
  } catch {
    return null;
  }
  if (!raw) return null;

  try {
    const value: unknown = JSON.parse(raw);
    if (!isRecord(value)
      || value.version !== 1
      || !isValidSelectionSeedUsageIds(value.mealUsageIds)
      || typeof value.createdAt !== "number"
      || !Number.isSafeInteger(value.createdAt)
      || Math.abs(Date.now() - value.createdAt) > POS_SETTLEMENT_SELECTION_SEED_TTL_MS) {
      return null;
    }
    return {
      version: 1,
      mealUsageIds: value.mealUsageIds,
      mealContractId: typeof value.mealContractId === "string" ? value.mealContractId : null,
      amountMinor: typeof value.amountMinor === "number" ? value.amountMinor : null,
      createdAt: value.createdAt,
    };
  } catch {
    return null;
  }
}

function isPositiveSafeInteger(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value > 0;
}

function isNonNegativeSafeInteger(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
}

function isIsoInstant(value: unknown): value is string {
  if (!isNonEmptyString(value)) return false;
  const match = /^(\d{4})-(\d{2})-(\d{2})T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?(?:Z|[+-]\d{2}:\d{2})$/.exec(value);
  if (!match || Number.isNaN(Date.parse(value))) return false;
  const [year, month, day] = match.slice(1).map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  return date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day;
}

function isIsoDate(value: unknown): value is string {
  if (!isNonEmptyString(value)) return false;
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) return false;
  const [year, month, day] = match.slice(1).map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  return date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day;
}

function parseCsrfToken(value: unknown): CsrfToken {
  if (!isRecord(value)
    || !isNonEmptyString(value.token)
    || !isNonEmptyString(value.headerName)
    || !isNonEmptyString(value.parameterName)) {
    throw new InvalidApiResponseError();
  }
  return { token: value.token, headerName: value.headerName, parameterName: value.parameterName };
}

function parseOutstandingReceivable(value: unknown): OutstandingReceivable {
  if (!isRecord(value)
    || !isUuid(value.mealUsageId)
    || !isUuid(value.mealContractId)
    || (value.partnerDisplayName !== null && !isNonEmptyString(value.partnerDisplayName))
    || !isIsoInstant(value.confirmedAt)
    || !isPositiveSafeInteger(value.receivableCreatedMinor)) {
    throw new InvalidApiResponseError();
  }
  return {
    mealUsageId: value.mealUsageId,
    mealContractId: value.mealContractId,
    partnerDisplayName: value.partnerDisplayName,
    confirmedAt: value.confirmedAt,
    receivableCreatedMinor: value.receivableCreatedMinor,
  };
}

function parsePartnerReceivableSummary(value: unknown): PartnerReceivableSummary {
  if (!isRecord(value)
    || !isUuid(value.mealContractId)
    || (value.partnerDisplayName !== null && !isNonEmptyString(value.partnerDisplayName))
    || (value.previousPosBusinessDate !== null && !isIsoDate(value.previousPosBusinessDate))
    || !isNonNegativeSafeInteger(value.periodConfirmedUsageTotalMinor)
    || !isNonNegativeSafeInteger(value.periodPrepaidAppliedTotalMinor)
    || !isNonNegativeSafeInteger(value.outstandingReceivableCount)
    || !isNonNegativeSafeInteger(value.outstandingReceivableTotalMinor)
    || value.periodPrepaidAppliedTotalMinor > value.periodConfirmedUsageTotalMinor) {
    throw new InvalidApiResponseError();
  }
  return {
    mealContractId: value.mealContractId,
    partnerDisplayName: value.partnerDisplayName,
    previousPosBusinessDate: value.previousPosBusinessDate,
    periodConfirmedUsageTotalMinor: value.periodConfirmedUsageTotalMinor,
    periodPrepaidAppliedTotalMinor: value.periodPrepaidAppliedTotalMinor,
    outstandingReceivableCount: value.outstandingReceivableCount,
    outstandingReceivableTotalMinor: value.outstandingReceivableTotalMinor,
  };
}

function parseOutstandingReceivableOverview(value: unknown): OutstandingReceivableOverview {
  if (!isRecord(value) || !Array.isArray(value.items) || !Array.isArray(value.partners)) {
    throw new InvalidApiResponseError();
  }
  const items = value.items.map(parseOutstandingReceivable);
  const partners = value.partners.map(parsePartnerReceivableSummary);
  const summariesByMealContractId = new Map<string, PartnerReceivableSummary>();
  for (const partner of partners) {
    if (summariesByMealContractId.has(partner.mealContractId)) {
      throw new InvalidApiResponseError();
    }
    summariesByMealContractId.set(partner.mealContractId, partner);
  }
  const candidatesByMealContractId = new Map<string, OutstandingReceivable[]>();
  for (const item of items) {
    if (!summariesByMealContractId.has(item.mealContractId)) {
      throw new InvalidApiResponseError();
    }
    const candidates = candidatesByMealContractId.get(item.mealContractId) ?? [];
    candidates.push(item);
    candidatesByMealContractId.set(item.mealContractId, candidates);
  }
  for (const partner of partners) {
    const candidates = candidatesByMealContractId.get(partner.mealContractId) ?? [];
    const candidateTotalMinor = candidates.reduce(
      (total, candidate) => total + candidate.receivableCreatedMinor,
      0,
    );
    if (!Number.isSafeInteger(candidateTotalMinor)
      || partner.outstandingReceivableCount !== candidates.length
      || partner.outstandingReceivableTotalMinor !== candidateTotalMinor) {
      throw new InvalidApiResponseError();
    }
  }
  return { items, partners };
}

function parsePosSettlementAllocation(value: unknown): PosSettlementAllocation {
  if (!isRecord(value)
    || (value.partnerDisplayName !== null && !isNonEmptyString(value.partnerDisplayName))
    || !isIsoInstant(value.confirmedAt)
    || !isPositiveSafeInteger(value.receivableAmountMinor)) {
    throw new InvalidApiResponseError();
  }
  return {
    partnerDisplayName: value.partnerDisplayName,
    confirmedAt: value.confirmedAt,
    receivableAmountMinor: value.receivableAmountMinor,
  };
}

function parsePosSettlement(value: unknown): PosSettlement {
  if (!isRecord(value)
    || !isIsoDate(value.posBusinessDate)
    || !isPositiveSafeInteger(value.submittedTotalMinor)
    || !isIsoInstant(value.recordedAt)
    || !Array.isArray(value.allocations)
    || value.allocations.length === 0) {
    throw new InvalidApiResponseError();
  }
  const posSettlementId = value.posSettlementId === undefined
    ? undefined
    : isUuid(value.posSettlementId) ? value.posSettlementId : (() => { throw new InvalidApiResponseError(); })();
  const allocations = value.allocations.map(parsePosSettlementAllocation);
  const allocationTotal = allocations.reduce((total, allocation) => total + allocation.receivableAmountMinor, 0);
  if (!Number.isSafeInteger(allocationTotal)
    || allocationTotal !== value.submittedTotalMinor) {
    throw new InvalidApiResponseError();
  }
  const parsed: PosSettlement = {
    posBusinessDate: value.posBusinessDate,
    submittedTotalMinor: value.submittedTotalMinor,
    recordedAt: value.recordedAt,
    allocations,
  };
  if (posSettlementId !== undefined) parsed.posSettlementId = posSettlementId;
  return parsed;
}

export type PosSettlementReceipt = {
  posSettlementId: string;
  fileName: string;
  contentType: "image/jpeg" | "image/png" | "application/pdf";
  sizeBytes: number;
  uploadedAt: string;
  expiresAt: string;
};

function parsePosSettlementReceipt(value: unknown): PosSettlementReceipt {
  if (!isRecord(value)
    || !isUuid(value.posSettlementId)
    || !isNonEmptyString(value.fileName)
    || (value.contentType !== "image/jpeg" && value.contentType !== "image/png" && value.contentType !== "application/pdf")
    || !isPositiveSafeInteger(value.sizeBytes)
    || !isIsoInstant(value.uploadedAt)
    || !isIsoInstant(value.expiresAt)) {
    throw new InvalidApiResponseError();
  }
  return {
    posSettlementId: value.posSettlementId,
    fileName: value.fileName,
    contentType: value.contentType,
    sizeBytes: value.sizeBytes,
    uploadedAt: value.uploadedAt,
    expiresAt: value.expiresAt,
  };
}

function parsePosSettlementHistoryPage(value: unknown): PosSettlementHistoryPage {
  if (!isRecord(value)
    || !Array.isArray(value.items)
    || value.page !== RECENT_SETTLEMENTS_PAGE
    || value.size !== RECENT_SETTLEMENTS_SIZE
    || typeof value.hasNext !== "boolean") {
    throw new InvalidApiResponseError();
  }
  const items = value.items.map(parsePosSettlement);
  return {
    items,
    page: value.page,
    size: value.size,
    hasNext: value.hasNext,
  };
}

async function apiError(response: Response): Promise<ApiError> {
  const body: unknown = await response.json().catch(() => null);
  const errorCode = isRecord(body) && typeof body.errorCode === "string" ? body.errorCode : undefined;
  return new ApiError(response.status, errorCode);
}

async function csrfToken(): Promise<CsrfToken> {
  const response = await fetch(`${API_PATH}/csrf`, {
    cache: "no-store",
    credentials: "same-origin",
  });
  if (!response.ok) {
    throw await apiError(response);
  }
  return parseCsrfToken(await response.json() as unknown);
}

export async function getOutstandingReceivables(): Promise<OutstandingReceivableOverview> {
  const response = await fetch(RECEIVABLES_PATH, {
    cache: "no-store",
    credentials: "same-origin",
  });
  if (!response.ok) {
    throw await apiError(response);
  }
  const body: unknown = await response.json();
  return parseOutstandingReceivableOverview(body);
}

export async function getRecentPosSettlements(): Promise<PosSettlementHistoryPage> {
  const response = await fetch(SETTLEMENTS_PATH + "?page=" + RECENT_SETTLEMENTS_PAGE + "&size=" + RECENT_SETTLEMENTS_SIZE, {
    cache: "no-store",
    credentials: "same-origin",
  });
  if (!response.ok) {
    throw await apiError(response);
  }
  return parsePosSettlementHistoryPage(await response.json() as unknown);
}

export async function recordPosSettlement(
  request: PosSettlementRequest,
  idempotencyKey: string,
): Promise<PosSettlement> {
  const csrf = await csrfToken();
  const response = await fetch(SETTLEMENTS_PATH, {
    method: "POST",
    cache: "no-store",
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/json",
      [csrf.headerName]: csrf.token,
      "Idempotency-Key": idempotencyKey,
    },
    body: JSON.stringify(request),
  });
  if (response.status !== 201) {
    if (response.ok) {
      throw new UnexpectedPosSettlementResponseError();
    }
    throw await apiError(response);
  }
  return parsePosSettlement(await response.json() as unknown);
}

export async function uploadPosSettlementReceipt(
  posSettlementId: string,
  file: File,
): Promise<PosSettlementReceipt> {
  if (!isUuid(posSettlementId)) throw new InvalidApiResponseError();
  const csrf = await csrfToken();
  const formData = new FormData();
  formData.append("file", file);
  const response = await fetch(`${SETTLEMENTS_PATH}/${posSettlementId}/receipt`, {
    method: "POST",
    cache: "no-store",
    credentials: "same-origin",
    headers: { [csrf.headerName]: csrf.token },
    body: formData,
  });
  if (response.status !== 201) {
    throw await apiError(response);
  }
  return parsePosSettlementReceipt(await response.json() as unknown);
}

export async function downloadPosSettlementReceipt(posSettlementId: string): Promise<Response> {
  if (!isUuid(posSettlementId)) throw new InvalidApiResponseError();
  const response = await fetch(`${SETTLEMENTS_PATH}/${posSettlementId}/receipt`, {
    cache: "no-store",
    credentials: "same-origin",
  });
  if (!response.ok) {
    throw await apiError(response);
  }
  return response;
}
