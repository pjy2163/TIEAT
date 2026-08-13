import { afterEach, describe, expect, it, vi } from "vitest";
import {
  cancelPublicMealUsage,
  createPublicMealUsage,
  getPublicMealUsageRequest,
  getPublicMealUsageQrContext,
  InvalidPublicQrApiResponseError,
  UnexpectedPublicQrCreationResponseError,
} from "./public-qr-api";

function response(status: number, body?: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn().mockResolvedValue(body),
  } as unknown as Response;
}

const token = "qR8wszyH5CXUTpt-Np5deNiRFi9OKKcjPCAwXWpEM5s";
const mealContractId = "00000000-0000-0000-0000-000000000001";
const publicRequestKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
const customerName = "홍길동";

describe("public QR API", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("loads only the public QR context without cookies or caching", async () => {
    const fetchMock = vi.fn().mockResolvedValue(response(200, {
      storeDisplayName: "강남점",
      partners: [{ mealContractId, partnerDisplayName: "협력사 A" }],
      qrExpiresAt: "2026-08-09T01:00:00Z",
    }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(getPublicMealUsageQrContext(token)).resolves.toEqual({
      storeDisplayName: "강남점",
      partners: [{ mealContractId, partnerDisplayName: "협력사 A" }],
      qrExpiresAt: "2026-08-09T01:00:00Z",
    });

    expect(fetchMock).toHaveBeenCalledWith(`/api/v1/public/meal-usage-qr/${token}`, {
      cache: "no-store",
      credentials: "omit",
    });
  });

  it("creates, reads, and cancels one request with headers instead of a QR or key body field", async () => {
    const pendingUsage = {
      mealUsageId: "00000000-0000-0000-0000-000000000002",
      status: "PENDING",
      amountMinor: 8_500,
      createdAt: "2026-08-09T01:00:00Z",
    };
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(201, pendingUsage))
      .mockResolvedValueOnce(response(200, pendingUsage))
      .mockResolvedValueOnce(response(200, { ...pendingUsage, status: "CANCELLED" }));
    vi.stubGlobal("fetch", fetchMock);
    const idempotencyKey = "00000000-0000-0000-0000-000000000003";

    await createPublicMealUsage(token, idempotencyKey, publicRequestKey, mealContractId, customerName, 8_500);
    await getPublicMealUsageRequest(token, pendingUsage.mealUsageId, idempotencyKey, publicRequestKey);
    await cancelPublicMealUsage(token, pendingUsage.mealUsageId, idempotencyKey, publicRequestKey);

    expect(fetchMock).toHaveBeenNthCalledWith(1, `/api/v1/public/meal-usage-qr/${token}/meal-usages`, {
      method: "POST",
      cache: "no-store",
      credentials: "omit",
      headers: {
        "Content-Type": "application/json",
        "Idempotency-Key": idempotencyKey,
        "Public-Request-Key": publicRequestKey,
      },
      body: JSON.stringify({ mealContractId, customerName, amountMinor: 8_500 }),
    });
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      `/api/v1/public/meal-usage-qr/${token}/meal-usages/${pendingUsage.mealUsageId}`,
      {
        cache: "no-store",
        credentials: "omit",
        headers: { "Idempotency-Key": idempotencyKey, "Public-Request-Key": publicRequestKey },
      }
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      `/api/v1/public/meal-usage-qr/${token}/meal-usages/${pendingUsage.mealUsageId}/cancellations`,
      {
        method: "POST",
        cache: "no-store",
        credentials: "omit",
        headers: { "Idempotency-Key": idempotencyKey, "Public-Request-Key": publicRequestKey },
      }
    );
  });

  it("rejects malformed public shapes and unexpected successful creation statuses", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response(200, {
      storeDisplayName: "강남점",
      partners: [{ mealContractId: "not-a-uuid", partnerDisplayName: "협력사 A" }],
      qrExpiresAt: "2026-08-09T01:00:00Z",
    })));
    await expect(getPublicMealUsageQrContext(token)).rejects.toEqual(new InvalidPublicQrApiResponseError());

    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response(200, {})));
    await expect(createPublicMealUsage(
      token, "00000000-0000-0000-0000-000000000003", publicRequestKey, mealContractId, customerName, 8_500
    ))
      .rejects.toEqual(new UnexpectedPublicQrCreationResponseError());
  });
});
