import { afterEach, describe, expect, it, vi } from "vitest";
import { getMonthlyMealUsages } from "./monthly-meal-usage-api";

function response(status: number, body?: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn().mockResolvedValue(body),
  } as unknown as Response;
}

const item = {
  id: "00000000-0000-0000-0000-000000000001",
  status: "CONFIRMED",
  partnerDisplayName: "협력사 A",
  amountMinor: 12_000,
  createdAt: "2026-08-05T01:00:00Z",
  confirmedStaffInitials: "HK",
};

describe("monthly meal usage API", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("gets only the server-scoped monthly page with no-store cache behavior", async () => {
    const fetchMock = vi.fn().mockResolvedValue(response(200, {
      month: "2026-08",
      fromMonth: "2026-08",
      toMonth: "2026-08",
      timeZone: "Asia/Seoul",
      items: [item],
      page: 1,
      size: 20,
      hasNext: false,
      totalAmountMinor: 12_000,
    }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(getMonthlyMealUsages("2026-08", 1, 20)).resolves.toEqual({
      month: "2026-08",
      fromMonth: "2026-08",
      toMonth: "2026-08",
      timeZone: "Asia/Seoul",
      items: [item],
      page: 1,
      size: 20,
      hasNext: false,
      totalAmountMinor: 12_000,
    });

    expect(fetchMock).toHaveBeenCalledWith("/api/v1/meal-usages/months/2026-08?page=1&size=20", {
      cache: "no-store",
      credentials: "same-origin",
    });
    expect(JSON.stringify(fetchMock.mock.calls)).not.toContain("storeId");
  });

  it("passes an optional inclusive end month and validates the server total", async () => {
    const fetchMock = vi.fn().mockResolvedValue(response(200, {
      month: "2026-07",
      fromMonth: "2026-07",
      toMonth: "2026-08",
      timeZone: "Asia/Seoul",
      items: [item],
      page: 0,
      size: 20,
      hasNext: false,
      totalAmountMinor: 24_000,
    }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(getMonthlyMealUsages("2026-07", "2026-08", 0, 20)).resolves.toMatchObject({
      fromMonth: "2026-07",
      toMonth: "2026-08",
      totalAmountMinor: 24_000,
    });
    expect(fetchMock).toHaveBeenCalledWith("/api/v1/meal-usages/months/2026-07?page=0&size=20&to=2026-08", {
      cache: "no-store",
      credentials: "same-origin",
    });
  });

  it("rejects malformed month pages, non-confirmed rows, and entry-source leakage", async () => {
    const invalidResponses = [
      { month: "2026-08", fromMonth: "2026-08", toMonth: "2026-08", timeZone: "Asia/Seoul", items: [{ ...item, confirmedStaffInitials: null }], page: 0, size: 20, hasNext: false, totalAmountMinor: 12_000 },
      { month: "2026-08", fromMonth: "2026-08", toMonth: "2026-08", timeZone: "Asia/Seoul", items: [{ ...item, status: "REJECTED", confirmedStaffInitials: "HK" }], page: 0, size: 20, hasNext: false, totalAmountMinor: 12_000 },
      { month: "2026-08", fromMonth: "2026-08", toMonth: "2026-08", timeZone: "Asia/Seoul", items: [{ ...item, entrySource: "PARTNER_MOBILE" }], page: 0, size: 20, hasNext: false, totalAmountMinor: 12_000 },
      { month: "2026-08", fromMonth: "2026-08", toMonth: "2026-08", timeZone: "UTC", items: [item], page: 0, size: 20, hasNext: false, totalAmountMinor: 12_000 },
      { month: "2026-08", fromMonth: "2026-08", toMonth: "2026-08", timeZone: "Asia/Seoul", items: [item], page: 0, size: 21, hasNext: false, totalAmountMinor: 12_000 },
      { month: "2026-08", fromMonth: "2026-08", toMonth: "2026-08", timeZone: "Asia/Seoul", items: [item], page: 0, size: 20, hasNext: false, totalAmountMinor: -1 },
    ];

    for (const body of invalidResponses) {
      vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response(200, body)));
      await expect(getMonthlyMealUsages("2026-08", 0, 20)).rejects.toMatchObject({
        status: 0,
        errorCode: "INVALID_API_RESPONSE",
      });
    }
  });

  it("does not issue a request for malformed client page input", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    await expect(getMonthlyMealUsages("2026-13", 0, 20)).rejects.toMatchObject({
      status: 0,
      errorCode: "INVALID_API_RESPONSE",
    });

    expect(fetchMock).not.toHaveBeenCalled();
  });
});
