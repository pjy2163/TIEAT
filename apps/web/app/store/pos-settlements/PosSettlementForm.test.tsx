import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/store-api";
import {
  downloadPosSettlementReceipt,
  getRecentPosSettlements,
  uploadPosSettlementReceipt,
  type PosSettlement,
  type PosSettlementHistoryPage,
} from "@/lib/pos-settlement-api";
import { PosSettlementForm } from "./PosSettlementForm";

const replace = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace }),
}));

vi.mock("@/lib/pos-settlement-api", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/lib/pos-settlement-api")>();
  return {
    ...original,
    downloadPosSettlementReceipt: vi.fn(),
    getRecentPosSettlements: vi.fn(),
    uploadPosSettlementReceipt: vi.fn(),
  };
});

const getRecentPosSettlementsMock = vi.mocked(getRecentPosSettlements);
const downloadPosSettlementReceiptMock = vi.mocked(downloadPosSettlementReceipt);
const uploadPosSettlementReceiptMock = vi.mocked(uploadPosSettlementReceipt);

const availableSettlementId = "00000000-0000-0000-0000-000000000101";
const expiredSettlementId = "00000000-0000-0000-0000-000000000102";
const legacySettlementId = "00000000-0000-0000-0000-000000000103";

const allocation = {
  partnerDisplayName: "협력사 A",
  confirmedAt: "2026-08-12T01:00:00Z",
  receivableAmountMinor: 12_000,
};

function settlement(
  posSettlementId: string | undefined,
  posBusinessDate: string,
  receipt?: PosSettlement["receipt"],
): PosSettlement {
  return {
    ...(posSettlementId ? { posSettlementId } : {}),
    posBusinessDate,
    submittedTotalMinor: 12_000,
    recordedAt: "2026-08-12T02:00:00Z",
    recordedByLoginId: "store-hk",
    allocations: [allocation],
    receipt,
  };
}

function historyPage(items: PosSettlement[], page = 0, hasNext = false): PosSettlementHistoryPage {
  return { items, page, size: 20, hasNext };
}

const availableReceipt = {
  status: "AVAILABLE" as const,
  fileName: "settlement.pdf",
  contentType: "application/pdf" as const,
  sizeBytes: 1_024,
  uploadedAt: "2026-08-12T02:00:00Z",
  expiresAt: "2027-08-12T02:00:00Z",
};

const expiredReceipt = {
  ...availableReceipt,
  status: "EXPIRED" as const,
  fileName: "expired-receipt.jpg",
  contentType: "image/jpeg" as const,
};

const noReceipt = {
  status: "NONE" as const,
  fileName: null,
  contentType: null,
  sizeBytes: null,
  uploadedAt: null,
  expiresAt: null,
};

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.resetAllMocks();
});

describe("PosSettlementForm history view", () => {
  it("renders saved payment records without the receivable list or record form", async () => {
    const saved = settlement(availableSettlementId, "2026-08-11", availableReceipt);
    getRecentPosSettlementsMock.mockResolvedValue(historyPage([saved]));

    render(<PosSettlementForm />);

    expect(await screen.findByRole("heading", { name: "결제 내역", level: 1 })).toBeVisible();
    expect(screen.getByRole("list", { name: "결제 내역 목록" })).toHaveTextContent("결제일 2026-08-11");
    expect(screen.queryByText("결제할 금액")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /기록하기/ })).not.toBeInTheDocument();
    expect(screen.queryByLabelText("영수증 첨부")).not.toBeInTheDocument();
    expect(getRecentPosSettlementsMock).toHaveBeenCalledWith(0);

    await userEvent.click(screen.getByRole("button", { name: "결제일 2026-08-11 상세 보기" }));
    expect(screen.getByText("영수증 있음")).toBeVisible();
    expect(screen.getByText("settlement.pdf")).toBeVisible();
    expect(screen.getByRole("button", { name: "영수증 다운로드" })).toBeVisible();
    expect(screen.getByText("결제 확인자")).toBeVisible();
    expect(screen.getByText("store-hk")).toBeVisible();
    expect(screen.queryByText(availableSettlementId)).not.toBeInTheDocument();
  });

  it("loads the previous and next history pages without splitting the screen into a form", async () => {
    const user = userEvent.setup();
    const firstPageSettlement = settlement(legacySettlementId, "2026-08-11", noReceipt);
    const secondPageSettlement = settlement(undefined, "2026-08-10", noReceipt);
    getRecentPosSettlementsMock
      .mockResolvedValueOnce(historyPage([firstPageSettlement], 0, true))
      .mockResolvedValueOnce(historyPage([secondPageSettlement], 1, false))
      .mockResolvedValueOnce(historyPage([firstPageSettlement], 0, true));

    render(<PosSettlementForm />);

    await screen.findByRole("heading", { name: "결제일 2026-08-11", level: 2 });
    expect(screen.getByText("페이지 1")).toBeVisible();
    expect(screen.getByRole("button", { name: "이전" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "다음" })).toBeEnabled();

    await user.click(screen.getByRole("button", { name: "다음" }));
    await waitFor(() => expect(getRecentPosSettlementsMock).toHaveBeenLastCalledWith(1));
    expect(await screen.findByRole("heading", { name: "결제일 2026-08-10", level: 2 })).toBeVisible();
    expect(screen.getByText("페이지 2")).toBeVisible();
    expect(screen.getByRole("button", { name: "이전" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "다음" })).toBeDisabled();

    await user.click(screen.getByRole("button", { name: "이전" }));
    await waitFor(() => expect(getRecentPosSettlementsMock).toHaveBeenLastCalledWith(0));
    expect(await screen.findByRole("heading", { name: "결제일 2026-08-11", level: 2 })).toBeVisible();
  });

  it("shows receipt presence safely and offers upload only when a receipt is missing", async () => {
    const user = userEvent.setup();
    getRecentPosSettlementsMock.mockResolvedValue(historyPage([
      settlement(availableSettlementId, "2026-08-11", availableReceipt),
      settlement(expiredSettlementId, "2026-08-10", expiredReceipt),
      settlement(legacySettlementId, "2026-08-09", noReceipt),
    ]));

    render(<PosSettlementForm />);

    await screen.findByRole("heading", { name: "결제일 2026-08-11", level: 2 });
    expect(screen.queryByLabelText("영수증 첨부")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "결제일 2026-08-11 상세 보기" }));
    expect(screen.getByText("영수증 있음")).toBeVisible();
    expect(screen.getByRole("button", { name: "영수증 다운로드" })).toBeVisible();
    expect(screen.queryByLabelText("영수증 첨부")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "결제일 2026-08-10 상세 보기" }));
    expect(screen.getByText("영수증 만료")).toBeVisible();
    expect(screen.getByText("expired-receipt.jpg")).toBeVisible();
    expect(screen.queryByLabelText("영수증 첨부")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "영수증 다운로드" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "결제일 2026-08-09 상세 보기" }));
    expect(screen.getByLabelText("영수증 첨부")).toBeVisible();
    expect(screen.queryByRole("button", { name: "영수증 다운로드" })).not.toBeInTheDocument();
    expect(screen.queryByText(availableSettlementId)).not.toBeInTheDocument();
    expect(screen.queryByText(expiredSettlementId)).not.toBeInTheDocument();
    expect(downloadPosSettlementReceiptMock).not.toHaveBeenCalled();
  });

  it("uploads a receipt from an expanded history item and updates its receipt state", async () => {
    const user = userEvent.setup();
    const saved = settlement(legacySettlementId, "2026-08-09", noReceipt);
    getRecentPosSettlementsMock.mockResolvedValue(historyPage([saved]));
    uploadPosSettlementReceiptMock.mockResolvedValue({
      posSettlementId: legacySettlementId,
      fileName: "history.pdf",
      contentType: "application/pdf",
      sizeBytes: 1_024,
      uploadedAt: "2026-08-12T03:00:00Z",
      expiresAt: "2027-08-12T03:00:00Z",
    });

    render(<PosSettlementForm />);

    await user.click(await screen.findByRole("button", { name: "결제일 2026-08-09 상세 보기" }));
    const receiptInput = screen.getByLabelText("영수증 첨부");
    await user.upload(receiptInput, new File(["%PDF-"], "history.pdf", { type: "application/pdf" }));

    await waitFor(() => expect(uploadPosSettlementReceiptMock).toHaveBeenCalledWith(
      legacySettlementId,
      expect.any(File),
    ));
    expect(screen.getByText("영수증 있음")).toBeVisible();
    expect(screen.getByText("history.pdf")).toBeVisible();
    expect(screen.getByRole("button", { name: "영수증 다운로드" })).toBeVisible();
    expect(screen.queryByLabelText("영수증 첨부")).not.toBeInTheDocument();
  });

  it("allows a failed history request to be retried without showing a false record success", async () => {
    const user = userEvent.setup();
    getRecentPosSettlementsMock
      .mockRejectedValueOnce(new ApiError(500, "HISTORY_UNAVAILABLE"))
      .mockResolvedValueOnce(historyPage([settlement(undefined, "2026-08-11", noReceipt)]));

    render(<PosSettlementForm />);

    expect(await screen.findByRole("alert")).toHaveTextContent("결제 내역을 불러오지 못했습니다.");
    expect(screen.queryByText("결제 기록 저장 완료")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "결제 내역 다시 불러오기" }));
    expect(await screen.findByRole("heading", { name: "결제일 2026-08-11", level: 2 })).toBeVisible();
  });
});
