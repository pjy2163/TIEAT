import { afterEach, describe, expect, it, vi } from "vitest";
import { getStorePartners, setArchivePin } from "./store-partner-api";

function response(status: number, body?: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn().mockResolvedValue(body),
  } as unknown as Response;
}

const partner = {
  mealContractId: "11111111-1111-4111-8111-111111111111",
  partnerOrganizationId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  partnerDisplayName: "가나다 협력사",
  partnerKind: "ORGANIZATION",
  paymentType: "POSTPAID",
  qrSelectable: true,
  representativePhone: null,
  representativeEmail: null,
} as const;

describe("store partner API", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("parses the seven-field store-scoped partner response with nullable contacts", async () => {
    const fetchMock = vi.fn().mockResolvedValue(response(200, [partner]));
    vi.stubGlobal("fetch", fetchMock);

    await expect(getStorePartners()).resolves.toEqual([partner]);
    expect(fetchMock).toHaveBeenCalledWith("/api/v1/store-partners", {
      cache: "no-store",
      credentials: "same-origin",
    });
  });

  it("accepts a legacy five-field response and maps omitted contacts to null", async () => {
    const { representativeEmail: _email, representativePhone: _phone, ...legacyPartner } = partner;
    const { partnerOrganizationId: _organizationId, ...legacyFiveFieldPartner } = legacyPartner;
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response(200, [legacyFiveFieldPartner])));

    await expect(getStorePartners()).resolves.toEqual([{
      ...legacyFiveFieldPartner,
      representativePhone: null,
      representativeEmail: null,
    }]);
  });

  it("sends account password and matching PIN confirmation for first-time setup", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(200, { token: "csrf-token", headerName: "X-CSRF-TOKEN" }))
      .mockResolvedValueOnce(response(200, { configured: true }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(setArchivePin({
      currentPin: null,
      accountPassword: "correct-password",
      newPin: "4321",
      newPinConfirmation: "4321",
    })).resolves.toEqual({ configured: true });
    expect(fetchMock).toHaveBeenNthCalledWith(2, "/api/v1/store-archive-pin", {
      method: "PUT",
      cache: "no-store",
      credentials: "same-origin",
      headers: {
        "Content-Type": "application/json",
        "X-CSRF-TOKEN": "csrf-token",
      },
      body: JSON.stringify({
        currentPin: null,
        accountPassword: "correct-password",
        newPin: "4321",
        newPinConfirmation: "4321",
      }),
    });
  });
});
