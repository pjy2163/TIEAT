import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ApiError,
  confirmMealUsage,
  getPendingMealUsages,
  login,
  rejectMealUsage,
  UnexpectedConfirmationResponseError,
  UnexpectedRejectionResponseError,
} from "./store-api";

function response(status: number, body?: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn().mockResolvedValue(body),
  } as unknown as Response;
}

describe("store API", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("gets CSRF before form login and uses the response-provided CSRF header", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(200, { token: "csrf-value", headerName: "X-CUSTOM-CSRF", parameterName: "_csrf" }))
      .mockResolvedValueOnce(response(204));
    vi.stubGlobal("fetch", fetchMock);

    await login("store-hk", "correct-password");

    expect(fetchMock).toHaveBeenNthCalledWith(1, "/api/v1/csrf", {
      cache: "no-store",
      credentials: "same-origin",
    });
    expect(fetchMock).toHaveBeenNthCalledWith(2, "/api/v1/sessions", expect.objectContaining({
      method: "POST",
      headers: {
        "Content-Type": "application/x-www-form-urlencoded",
        "X-CUSTOM-CSRF": "csrf-value",
      },
      body: expect.any(URLSearchParams),
    }));
    const body = fetchMock.mock.calls[1][1].body as URLSearchParams;
    expect(body.toString()).toBe("loginId=store-hk&password=correct-password");
  });

  it("always requests only the fixed server-scoped pending page", async () => {
    const fetchMock = vi.fn().mockResolvedValue(response(200, { items: [], page: 0, size: 50, hasNext: false }));
    vi.stubGlobal("fetch", fetchMock);

    await getPendingMealUsages();

    expect(fetchMock).toHaveBeenCalledWith("/api/v1/meal-usages?status=PENDING&page=0&size=50", {
      cache: "no-store",
      credentials: "same-origin",
    });
    expect(JSON.stringify(fetchMock.mock.calls)).not.toContain("storeId");
  });

  it("gets a fresh CSRF token for confirmation and sends only the original initials", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(200, { token: "fresh-csrf", headerName: "X-ROTATED-CSRF", parameterName: "_csrf" }))
      .mockResolvedValueOnce(response(201));
    vi.stubGlobal("fetch", fetchMock);

    await confirmMealUsage("00000000-0000-0000-0000-000000000001", " Hk ");

    expect(fetchMock).toHaveBeenNthCalledWith(1, "/api/v1/csrf", {
      cache: "no-store",
      credentials: "same-origin",
    });
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      "/api/v1/meal-usages/00000000-0000-0000-0000-000000000001/confirmations",
      {
        method: "POST",
        cache: "no-store",
        credentials: "same-origin",
        headers: {
          "Content-Type": "application/json",
          "X-ROTATED-CSRF": "fresh-csrf",
        },
        body: JSON.stringify({ confirmerInitials: " Hk " }),
      },
    );
    expect(JSON.stringify(fetchMock.mock.calls)).not.toContain("storeId");
    expect(JSON.stringify(fetchMock.mock.calls)).not.toContain("expectedVersion");
  });

  it("preserves structured confirmation errors from the CSRF or confirmation request", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(200, { token: "csrf", headerName: "X-CSRF-TOKEN", parameterName: "_csrf" }))
      .mockResolvedValueOnce(response(409, { errorCode: "MEAL_USAGE_ALREADY_CONFIRMED" }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(confirmMealUsage("00000000-0000-0000-0000-000000000001", "HK"))
      .rejects.toEqual(new ApiError(409, "MEAL_USAGE_ALREADY_CONFIRMED"));

    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response(403, { errorCode: "CSRF_TOKEN_INVALID" })));
    await expect(confirmMealUsage("00000000-0000-0000-0000-000000000001", "HK"))
      .rejects.toEqual(new ApiError(403, "CSRF_TOKEN_INVALID"));
  });

  it("rejects unexpected successful confirmation statuses as a protocol error", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(200, { token: "csrf", headerName: "X-CSRF-TOKEN", parameterName: "_csrf" }))
      .mockResolvedValueOnce(response(204));
    vi.stubGlobal("fetch", fetchMock);

    await expect(confirmMealUsage("00000000-0000-0000-0000-000000000001", "HK"))
      .rejects.toEqual(new UnexpectedConfirmationResponseError());
  });

  it("gets fresh CSRF before staff rejection and rejects unexpected successful statuses", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(200, { token: "fresh-csrf", headerName: "X-CSRF-TOKEN", parameterName: "_csrf" }))
      .mockResolvedValueOnce(response(201));
    vi.stubGlobal("fetch", fetchMock);

    await rejectMealUsage("00000000-0000-0000-0000-000000000001");

    expect(fetchMock).toHaveBeenNthCalledWith(2, "/api/v1/meal-usages/00000000-0000-0000-0000-000000000001/rejections", {
      method: "POST",
      cache: "no-store",
      credentials: "same-origin",
      headers: { "X-CSRF-TOKEN": "fresh-csrf" },
    });

    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce(response(200, { token: "fresh-csrf", headerName: "X-CSRF-TOKEN", parameterName: "_csrf" }))
      .mockResolvedValueOnce(response(204)));
    await expect(rejectMealUsage("00000000-0000-0000-0000-000000000001"))
      .rejects.toEqual(new UnexpectedRejectionResponseError());
  });

  it("rejects malformed CSRF and pending list responses as a stable API error", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(200, { token: "csrf", headerName: "X-CSRF-TOKEN" }))
      .mockResolvedValueOnce(response(200, {
        items: [{
          mealUsageId: "00000000-0000-0000-0000-000000000001",
          status: "PENDING",
        entrySource: "STORE_TABLET",
        partnerDisplayName: null,
          amountMinor: 12_000.5,
          createdAt: "not-a-date",
        }],
        page: 0,
        size: 50,
        hasNext: false,
      }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(login("store-hk", "correct-password")).rejects.toMatchObject({
      status: 0,
      errorCode: "INVALID_API_RESPONSE",
    });
    await expect(getPendingMealUsages()).rejects.toMatchObject({
      status: 0,
      errorCode: "INVALID_API_RESPONSE",
    });
  });

  it("accepts only supported pending values while ignoring additive fields", async () => {
    const fetchMock = vi.fn().mockResolvedValue(response(200, {
      items: [{
        mealUsageId: "00000000-0000-0000-0000-000000000001",
        status: "PENDING",
        entrySource: "PARTNER_MOBILE",
        partnerDisplayName: "협력사 A",
        amountMinor: 12_000,
        createdAt: "2026-08-05T01:00:00Z",
        additiveField: "ignored",
      }],
      page: 0,
      size: 50,
      hasNext: false,
      additiveField: "ignored",
    }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(getPendingMealUsages()).resolves.toEqual({
      items: [{
        mealUsageId: "00000000-0000-0000-0000-000000000001",
        status: "PENDING",
        entrySource: "PARTNER_MOBILE",
        partnerDisplayName: "협력사 A",
        amountMinor: 12_000,
        createdAt: "2026-08-05T01:00:00Z",
      }],
      page: 0,
      size: 50,
      hasNext: false,
    });
  });

  it("rejects a non-UUID usage ID and createdAt values without a timezone instant", async () => {
    const invalidItems = [
      { ...validPendingItem(), mealUsageId: "not-a-uuid" },
      { ...validPendingItem(), createdAt: "2026-08-05" },
      { ...validPendingItem(), createdAt: "2026-08-05T01:00:00" },
    ];

    for (const item of invalidItems) {
      vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response(200, {
        items: [item],
        page: 0,
        size: 50,
        hasNext: false,
      })));
      await expect(getPendingMealUsages()).rejects.toMatchObject({
        status: 0,
        errorCode: "INVALID_API_RESPONSE",
      });
    }
  });
});

function validPendingItem() {
  return {
    mealUsageId: "00000000-0000-0000-0000-000000000001",
    status: "PENDING",
    entrySource: "STORE_TABLET",
    partnerDisplayName: null,
    amountMinor: 12_000,
    createdAt: "2026-08-05T01:00:00Z",
  };
}
