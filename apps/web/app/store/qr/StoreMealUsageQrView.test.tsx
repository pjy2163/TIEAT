import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { getStoreMealUsageQr, renewStoreMealUsageQr } from "@/lib/store-meal-usage-qr-api";
import { StoreMealUsageQrView } from "./StoreMealUsageQrView";

const qrMocks = vi.hoisted(() => ({
  getStoreMealUsageQr: vi.fn(),
  renewStoreMealUsageQr: vi.fn(),
  toDataURL: vi.fn(),
}));

vi.mock("@/lib/store-meal-usage-qr-api", () => ({
  getStoreMealUsageQr: qrMocks.getStoreMealUsageQr,
  renewStoreMealUsageQr: qrMocks.renewStoreMealUsageQr,
}));

vi.mock("qrcode", () => ({
  default: {
    toDataURL: qrMocks.toDataURL,
  },
}));

vi.mock("../StorePartnerContext", () => ({
  useStorePartnerContext: () => ({ storeDisplayName: "써브웨이 숙명여대점" }),
}));

const availableView = {
  status: "AVAILABLE" as const,
  publicPath: `/qr/${"a".repeat(43)}`,
  issuedAt: "2026-08-20T00:00:00Z",
  expiresAt: "2026-11-18T00:00:00Z",
};

beforeEach(() => {
  vi.clearAllMocks();
  qrMocks.getStoreMealUsageQr.mockResolvedValue(availableView);
  qrMocks.renewStoreMealUsageQr.mockResolvedValue(availableView);
  qrMocks.toDataURL.mockResolvedValue("data:image/png;base64,local-qr");
});

afterEach(() => {
  cleanup();
});

describe("StoreMealUsageQrView", () => {
  it("fetches the store QR view and renders a locally encoded QR without persistence", async () => {
    const setItemSpy = vi.spyOn(Storage.prototype, "setItem");

    render(<StoreMealUsageQrView />);

    const image = await screen.findByRole("img", { name: "써브웨이 숙명여대점 QR코드" });
    expect(image).toHaveAttribute("src", "data:image/png;base64,local-qr");
    expect(getStoreMealUsageQr).toHaveBeenCalledTimes(1);
    expect(qrMocks.toDataURL).toHaveBeenCalledWith(`${window.location.origin}${availableView.publicPath}`, expect.objectContaining({
      errorCorrectionLevel: "M",
    }));
    expect(screen.getByText(/만료:/)).toBeInTheDocument();
    expect(setItemSpy).not.toHaveBeenCalled();

    setItemSpy.mockRestore();
  });

  it.each([
    ["NOT_AVAILABLE", "QR코드가 아직 없습니다"],
    ["REISSUE_REQUIRED", "QR코드를 다시 발급해 주세요"],
  ] as const)("shows the %s status without mutation controls", async (status, title) => {
    qrMocks.getStoreMealUsageQr.mockResolvedValue({
      status,
      publicPath: null,
      issuedAt: status === "NOT_AVAILABLE" ? null : "2026-08-20T00:00:00Z",
      expiresAt: status === "NOT_AVAILABLE" ? null : "2026-11-18T00:00:00Z",
    });

    render(<StoreMealUsageQrView />);

    expect(await screen.findByRole("heading", { name: title })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /발급|재발급|폐기|철회/ })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "장부로 돌아가기" })).toHaveAttribute("href", "/store/meal-usages/months");
  });

  it("renews an expired QR and renders the same QR with an extended expiry", async () => {
    const user = userEvent.setup();
    const renewedView = {
      status: "AVAILABLE" as const,
      publicPath: availableView.publicPath,
      issuedAt: "2026-08-20T00:00:00Z",
      expiresAt: "2027-02-16T00:00:00Z",
    };
    qrMocks.getStoreMealUsageQr.mockResolvedValue({
      status: "EXPIRED",
      publicPath: null,
      issuedAt: "2026-08-20T00:00:00Z",
      expiresAt: "2026-11-18T00:00:00Z",
    });
    qrMocks.renewStoreMealUsageQr.mockResolvedValue(renewedView);

    render(<StoreMealUsageQrView />);

    await user.click(await screen.findByRole("button", { name: "90일 연장하기" }));
    await waitFor(() => expect(renewStoreMealUsageQr).toHaveBeenCalledTimes(1));
    expect(await screen.findByRole("img", { name: "써브웨이 숙명여대점 QR코드" })).toBeInTheDocument();
    expect(qrMocks.toDataURL).toHaveBeenLastCalledWith(
      `${window.location.origin}${renewedView.publicPath}`,
      expect.objectContaining({ errorCorrectionLevel: "M" }),
    );
  });

  it("recovers from an API error with one explicit retry", async () => {
    const user = userEvent.setup();
    qrMocks.getStoreMealUsageQr
      .mockRejectedValueOnce(new Error("network"))
      .mockResolvedValueOnce({
        status: "NOT_AVAILABLE",
        publicPath: null,
        issuedAt: null,
        expiresAt: null,
      });

    render(<StoreMealUsageQrView />);

    expect(await screen.findByRole("alert")).toHaveTextContent("QR코드를 불러오지 못했습니다");
    await user.click(screen.getByRole("button", { name: "다시 시도" }));
    await waitFor(() => expect(getStoreMealUsageQr).toHaveBeenCalledTimes(2));
    expect(await screen.findByRole("heading", { name: "QR코드가 아직 없습니다" })).toBeInTheDocument();
  });
});
