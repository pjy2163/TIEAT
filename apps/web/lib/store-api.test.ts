import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ApiError,
  confirmMealUsage,
  getStoreOnboardingStatus,
  getPendingMealUsages,
  login,
  reauthenticateStoreSession,
  registerFirstPartner,
  rejectMealUsage,
  searchStorePlaces,
  signUpStoreAccount,
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

  it("adds the remembered-login form field only when explicitly requested", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(200, { token: "csrf-value", headerName: "X-CSRF-TOKEN", parameterName: "_csrf" }))
      .mockResolvedValueOnce(response(204));
    vi.stubGlobal("fetch", fetchMock);

    await login("store-hk", "correct-password", true);

    const body = fetchMock.mock.calls[1][1].body as URLSearchParams;
    expect(body.toString()).toBe("loginId=store-hk&password=correct-password&rememberLogin=true");
  });

  it("gets fresh CSRF and accepts only the 204 reauthentication response", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(200, { token: "reauth-csrf", headerName: "X-REAUTH-CSRF", parameterName: "_csrf" }))
      .mockResolvedValueOnce(response(204));
    vi.stubGlobal("fetch", fetchMock);

    await reauthenticateStoreSession("correct-password");

    expect(fetchMock).toHaveBeenNthCalledWith(2, "/api/v1/session-reauthentications", {
      method: "POST",
      cache: "no-store",
      credentials: "same-origin",
      headers: {
        "Content-Type": "application/json",
        "X-REAUTH-CSRF": "reauth-csrf",
      },
      body: JSON.stringify({ password: "correct-password" }),
    });

    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce(response(200, { token: "reauth-csrf", headerName: "X-CSRF-TOKEN", parameterName: "_csrf" }))
      .mockResolvedValueOnce(response(200)));
    await expect(reauthenticateStoreSession("correct-password")).rejects.toMatchObject({
      status: 0,
      errorCode: "INVALID_API_RESPONSE",
    });
  });

  it("uses CSRF for signup and sends no client-controlled store scope", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(200, { token: "signup-csrf", headerName: "X-CSRF-TOKEN", parameterName: "_csrf" }))
      .mockResolvedValueOnce(response(201, { onboardingStatus: "PARTNER_REQUIRED" }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(signUpStoreAccount({
      inviteCode: "pilot-code",
      loginId: "store-hk",
      password: "correct-password",
      manualStoreName: "TIEAT 강남점",
    })).resolves.toEqual({ onboardingStatus: "PARTNER_REQUIRED", legacy: false });

    expect(fetchMock).toHaveBeenNthCalledWith(2, "/api/v1/store-signups", {
      method: "POST",
      cache: "no-store",
      credentials: "same-origin",
      headers: {
        "Content-Type": "application/json",
        "X-CSRF-TOKEN": "signup-csrf",
      },
      body: JSON.stringify({
        inviteCode: "pilot-code",
        loginId: "store-hk",
        password: "correct-password",
        manualStoreName: "TIEAT 강남점",
      }),
    });
    expect(JSON.stringify(fetchMock.mock.calls)).not.toContain("storeId");
  });

  it("uses CSRF for a Kakao store-place projection and checks onboarding before partner recovery", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(200, { token: "place-csrf", headerName: "X-PLACE-CSRF", parameterName: "_csrf" }))
      .mockResolvedValueOnce(response(200, {
        source: "KAKAO",
        items: [{
          placeId: "26338954",
          storeDisplayName: "TIEAT 강남점",
          address: "서울 강남구 테헤란로 123",
          category: "음식점 > 한식",
        }],
      }))
      .mockResolvedValueOnce(response(200, { onboardingStatus: "PARTNER_REQUIRED", legacy: false }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(searchStorePlaces({ inviteCode: "pilot-code", query: "TIEAT" })).resolves.toEqual([{
      placeId: "26338954",
      storeDisplayName: "TIEAT 강남점",
      address: "서울 강남구 테헤란로 123",
      category: "음식점 > 한식",
    }]);
    await expect(getStoreOnboardingStatus()).resolves.toEqual({ onboardingStatus: "PARTNER_REQUIRED", legacy: false });

    expect(fetchMock).toHaveBeenNthCalledWith(1, "/api/v1/csrf", {
      cache: "no-store",
      credentials: "same-origin",
    });
    expect(fetchMock).toHaveBeenNthCalledWith(2, "/api/v1/store-place-searches", {
      method: "POST",
      cache: "no-store",
      credentials: "same-origin",
      headers: {
        "Content-Type": "application/json",
        "X-PLACE-CSRF": "place-csrf",
      },
      body: JSON.stringify({ inviteCode: "pilot-code", query: "TIEAT" }),
    });
    expect(fetchMock).toHaveBeenNthCalledWith(3, "/api/v1/store-onboarding", {
      cache: "no-store",
      credentials: "same-origin",
    });
  });

  it("posts every explicit first-partner contract field with a fresh CSRF token", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(200, { token: "partner-csrf", headerName: "X-PARTNER-CSRF", parameterName: "_csrf" }))
      .mockResolvedValueOnce(response(201, {
        onboardingStatus: "COMPLETE",
        legacy: false,
        created: true,
        partnerDisplayName: "협력사 A",
        partnerKind: "ORGANIZATION",
        paymentType: "POSTPAID",
        mealContractId: "33333333-3333-4333-8333-333333333333",
      }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(registerFirstPartner({
      partnerName: "협력사 A",
      partnerKind: "ORGANIZATION",
      paymentType: "POSTPAID",
      initialPrepaidBalanceMinor: 0,
      qrSelectable: true,
    })).resolves.toMatchObject({ onboardingStatus: "COMPLETE", created: true });

    expect(fetchMock).toHaveBeenNthCalledWith(2, "/api/v1/store-onboarding/partners", {
      method: "POST",
      cache: "no-store",
      credentials: "same-origin",
      headers: {
        "Content-Type": "application/json",
        "X-PARTNER-CSRF": "partner-csrf",
      },
      body: JSON.stringify({
        partnerName: "협력사 A",
        partnerKind: "ORGANIZATION",
        paymentType: "POSTPAID",
        initialPrepaidBalanceMinor: 0,
        qrSelectable: true,
      }),
    });
    expect(JSON.stringify(fetchMock.mock.calls)).not.toContain("storeId");
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
        customerName: null,
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
        customerName: "홍길동",
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
        customerName: "홍길동",
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
    customerName: null,
    amountMinor: 12_000,
    createdAt: "2026-08-05T01:00:00Z",
  };
}
