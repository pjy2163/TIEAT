import { afterEach, describe, expect, it, vi } from "vitest";
import {
  getRecentPosSettlements,
  getOutstandingReceivables,
  recordPosSettlement,
  takePosSettlementSelectionSeed,
  writePosSettlementSelectionSeed,
  POS_SETTLEMENT_SELECTION_SEED_STORAGE_KEY,
  UnexpectedPosSettlementResponseError,
} from "./pos-settlement-api";
import { ApiError } from "./store-api";

function response(status: number, body?: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn().mockResolvedValue(body),
  } as unknown as Response;
}

const receivable = {
  mealUsageId: "00000000-0000-0000-0000-000000000001",
  mealContractId: "00000000-0000-0000-0000-000000000002",
  partnerDisplayName: "협력사 A",
  confirmedAt: "2026-08-12T01:00:00Z",
  receivableCreatedMinor: 12_000,
};

const partnerSummary = {
  mealContractId: receivable.mealContractId,
  partnerDisplayName: receivable.partnerDisplayName,
  previousPosBusinessDate: "2026-08-11",
  periodConfirmedUsageTotalMinor: 12_000,
  periodPrepaidAppliedTotalMinor: 0,
  outstandingReceivableCount: 1,
  outstandingReceivableTotalMinor: 12_000,
};

const receivableOverview = {
  items: [receivable],
  partners: [partnerSummary],
};

const settlement = {
  posBusinessDate: "2026-08-11",
  submittedTotalMinor: 12_000,
  recordedAt: "2026-08-12T02:00:00Z",
  allocations: [{
    partnerDisplayName: receivable.partnerDisplayName,
    confirmedAt: receivable.confirmedAt,
    receivableAmountMinor: 12_000,
  }],
};

const historyPage = {
  items: [settlement],
  page: 0,
  size: 20,
  hasNext: false,
};

describe("POS settlement API", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("gets only the server-scoped outstanding receivable projection", async () => {
    const fetchMock = vi.fn().mockResolvedValue(response(200, receivableOverview));
    vi.stubGlobal("fetch", fetchMock);

    await expect(getOutstandingReceivables()).resolves.toEqual(receivableOverview);

    expect(fetchMock).toHaveBeenCalledWith("/api/v1/pos-settlements/receivables", {
      cache: "no-store",
      credentials: "same-origin",
    });
    expect(JSON.stringify(fetchMock.mock.calls)).not.toContain("storeId");
  });

  it("gets recent saved settlement history from the server-scoped API", async () => {
    const fetchMock = vi.fn().mockResolvedValue(response(200, historyPage));
    vi.stubGlobal("fetch", fetchMock);

    await expect(getRecentPosSettlements()).resolves.toEqual(historyPage);

    expect(fetchMock).toHaveBeenCalledWith("/api/v1/pos-settlements?page=0&size=20", {
      cache: "no-store",
      credentials: "same-origin",
    });
    expect(JSON.stringify(fetchMock.mock.calls)).not.toContain("storeId");
  });

  it("gets fresh CSRF and sends the complete POS attestation without a client store scope", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(200, { token: "csrf", headerName: "X-CSRF-TOKEN", parameterName: "_csrf" }))
      .mockResolvedValueOnce(response(201, settlement));
    vi.stubGlobal("fetch", fetchMock);
    const request = {
      mealContractId: receivable.mealContractId,
      posBusinessDate: "2026-08-11",
      submittedTotalMinor: 12_000,
      mealUsageIds: [receivable.mealUsageId],
    };

    await expect(recordPosSettlement(request, "00000000-0000-0000-0000-000000000004")).resolves.toEqual(settlement);

    expect(fetchMock).toHaveBeenNthCalledWith(1, "/api/v1/csrf", {
      cache: "no-store",
      credentials: "same-origin",
    });
    expect(fetchMock).toHaveBeenNthCalledWith(2, "/api/v1/pos-settlements", {
      method: "POST",
      cache: "no-store",
      credentials: "same-origin",
      headers: {
        "Content-Type": "application/json",
        "X-CSRF-TOKEN": "csrf",
        "Idempotency-Key": "00000000-0000-0000-0000-000000000004",
      },
      body: JSON.stringify(request),
    });
    expect(JSON.stringify(fetchMock.mock.calls)).not.toContain("storeId");
  });

  it("preserves settlement conflict errors and rejects malformed or unexpected success responses", async () => {
    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce(response(200, { token: "csrf", headerName: "X-CSRF-TOKEN", parameterName: "_csrf" }))
      .mockResolvedValueOnce(response(409, { errorCode: "POS_SETTLEMENT_TOTAL_MISMATCH" })));
    await expect(recordPosSettlement({
      mealContractId: receivable.mealContractId,
      posBusinessDate: "2026-08-11",
      submittedTotalMinor: 12_000,
      mealUsageIds: [receivable.mealUsageId],
    }, "00000000-0000-0000-0000-000000000004"))
      .rejects.toEqual(new ApiError(409, "POS_SETTLEMENT_TOTAL_MISMATCH"));

    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response(200, { items: [{ ...receivable, receivableCreatedMinor: 0 }] })));
    await expect(getOutstandingReceivables()).rejects.toBeInstanceOf(ApiError);

    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response(200, {
      ...historyPage,
      items: [{
        ...settlement,
        allocations: [{ ...settlement.allocations[0], confirmedAt: "not-an-instant" }],
      }],
    })));
    await expect(getRecentPosSettlements()).rejects.toBeInstanceOf(ApiError);

    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response(200, {
      ...historyPage,
      items: [{ ...settlement, submittedTotalMinor: 12_001 }],
    })));
    await expect(getRecentPosSettlements()).rejects.toBeInstanceOf(ApiError);

    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce(response(200, { token: "csrf", headerName: "X-CSRF-TOKEN", parameterName: "_csrf" }))
      .mockResolvedValueOnce(response(204)));
    await expect(recordPosSettlement({
      mealContractId: receivable.mealContractId,
      posBusinessDate: "2026-08-11",
      submittedTotalMinor: 12_000,
      mealUsageIds: [receivable.mealUsageId],
    }, "00000000-0000-0000-0000-000000000004"))
      .rejects.toEqual(new UnexpectedPosSettlementResponseError());
  });

  it("writes and consumes a short-lived same-tab selection seed while leaving amount and contract untrusted", () => {
    const usageId = receivable.mealUsageId;
    expect(writePosSettlementSelectionSeed({
      mealUsageIds: [usageId],
      mealContractId: receivable.mealContractId,
      amountMinor: 12_000,
    })).toBe(true);
    expect(window.sessionStorage.getItem(POS_SETTLEMENT_SELECTION_SEED_STORAGE_KEY)).toContain(usageId);

    window.sessionStorage.setItem(POS_SETTLEMENT_SELECTION_SEED_STORAGE_KEY, JSON.stringify({
      version: 1,
      mealUsageIds: [usageId],
      mealContractId: "tampered-contract",
      amountMinor: 1,
      createdAt: Date.now(),
    }));
    expect(takePosSettlementSelectionSeed()).toMatchObject({
      mealUsageIds: [usageId],
      mealContractId: "tampered-contract",
      amountMinor: 1,
    });
    expect(window.sessionStorage.getItem(POS_SETTLEMENT_SELECTION_SEED_STORAGE_KEY)).toBeNull();
    expect(takePosSettlementSelectionSeed()).toBeNull();
  });
});
