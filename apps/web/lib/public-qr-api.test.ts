import { afterEach, describe, expect, it, vi } from "vitest";
import {
  createPublicMealUsage,
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

  it("creates a pending request with only contract amount and UUID key, never a cookie or QR body field", async () => {
    const fetchMock = vi.fn().mockResolvedValue(response(201, {
      mealUsageId: "00000000-0000-0000-0000-000000000002",
      status: "PENDING",
      amountMinor: 8_500,
      createdAt: "2026-08-09T01:00:00Z",
    }));
    vi.stubGlobal("fetch", fetchMock);
    const idempotencyKey = "00000000-0000-0000-0000-000000000003";

    await createPublicMealUsage(token, idempotencyKey, mealContractId, 8_500);

    expect(fetchMock).toHaveBeenCalledWith(`/api/v1/public/meal-usage-qr/${token}/meal-usages`, {
      method: "POST",
      cache: "no-store",
      credentials: "omit",
      headers: {
        "Content-Type": "application/json",
        "Idempotency-Key": idempotencyKey,
      },
      body: JSON.stringify({ mealContractId, amountMinor: 8_500 }),
    });
  });

  it("rejects malformed public shapes and unexpected successful creation statuses", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response(200, {
      storeDisplayName: "강남점",
      partners: [{ mealContractId: "not-a-uuid", partnerDisplayName: "협력사 A" }],
      qrExpiresAt: "2026-08-09T01:00:00Z",
    })));
    await expect(getPublicMealUsageQrContext(token)).rejects.toEqual(new InvalidPublicQrApiResponseError());

    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response(200, {})));
    await expect(createPublicMealUsage(token, "00000000-0000-0000-0000-000000000003", mealContractId, 8_500))
      .rejects.toEqual(new UnexpectedPublicQrCreationResponseError());
  });
});
