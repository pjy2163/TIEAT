import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  getStoreMealUsageQr,
  pauseStoreMealUsageQr,
  renewStoreMealUsageQr,
  resumeStoreMealUsageQr,
} from "@/lib/store-meal-usage-qr-api";
import { StoreMealUsageQrView } from "./StoreMealUsageQrView";

const qrMocks = vi.hoisted(() => ({
  getStoreMealUsageQr: vi.fn(),
  pauseStoreMealUsageQr: vi.fn(),
  renewStoreMealUsageQr: vi.fn(),
  resumeStoreMealUsageQr: vi.fn(),
  toDataURL: vi.fn(),
}));

vi.mock("@/lib/store-meal-usage-qr-api", () => ({
  getStoreMealUsageQr: qrMocks.getStoreMealUsageQr,
  pauseStoreMealUsageQr: qrMocks.pauseStoreMealUsageQr,
  renewStoreMealUsageQr: qrMocks.renewStoreMealUsageQr,
  resumeStoreMealUsageQr: qrMocks.resumeStoreMealUsageQr,
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
  acceptingNewRequests: true,
};

beforeEach(() => {
  vi.clearAllMocks();
  qrMocks.getStoreMealUsageQr.mockResolvedValue(availableView);
  qrMocks.pauseStoreMealUsageQr.mockResolvedValue({ ...availableView, acceptingNewRequests: false });
  qrMocks.renewStoreMealUsageQr.mockResolvedValue(availableView);
  qrMocks.resumeStoreMealUsageQr.mockResolvedValue(availableView);
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

  it("pauses and resumes new requests without changing the public QR", async () => {
    const user = userEvent.setup();
    render(<StoreMealUsageQrView />);
    const qrImage = await screen.findByRole("img", { name: "써브웨이 숙명여대점 QR코드" });
    const publicPath = availableView.publicPath;

    await user.click(screen.getByRole("button", { name: "새 요청 일시 중지" }));
    await waitFor(() => expect(qrMocks.pauseStoreMealUsageQr).toHaveBeenCalledTimes(1));
    expect(screen.getByText("새 요청은 일시 중지되었습니다. 기존 대기 요청은 계속 처리할 수 있습니다.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "새 요청 재개" })).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "써브웨이 숙명여대점 QR코드" })).toHaveAttribute("src", qrImage.getAttribute("src"));
    expect(qrMocks.toDataURL).toHaveBeenLastCalledWith(
      `${window.location.origin}${publicPath}`,
      expect.objectContaining({ errorCorrectionLevel: "M" }),
    );

    await user.click(screen.getByRole("button", { name: "새 요청 재개" }));
    await waitFor(() => expect(qrMocks.resumeStoreMealUsageQr).toHaveBeenCalledTimes(1));
    expect(screen.getByText("새 요청을 받고 있습니다.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "새 요청 일시 중지" })).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "써브웨이 숙명여대점 QR코드" })).toBeInTheDocument();

    qrMocks.pauseStoreMealUsageQr.mockRejectedValueOnce(new Error("pause failed"));
    await user.click(screen.getByRole("button", { name: "새 요청 일시 중지" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("QR 요청 설정을 변경하지 못했습니다");
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
      acceptingNewRequests: true,
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
      acceptingNewRequests: true,
    };
    qrMocks.getStoreMealUsageQr.mockResolvedValue({
      status: "EXPIRED",
      publicPath: null,
      issuedAt: "2026-08-20T00:00:00Z",
      expiresAt: "2026-11-18T00:00:00Z",
      acceptingNewRequests: true,
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
        acceptingNewRequests: true,
      });

    render(<StoreMealUsageQrView />);

    expect(await screen.findByRole("alert")).toHaveTextContent("QR코드를 불러오지 못했습니다");
    await user.click(screen.getByRole("button", { name: "다시 시도" }));
    await waitFor(() => expect(getStoreMealUsageQr).toHaveBeenCalledTimes(2));
    expect(await screen.findByRole("heading", { name: "QR코드가 아직 없습니다" })).toBeInTheDocument();
  });
});
