import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/store-api";
import {
  getOutstandingReceivables,
  getRecentPosSettlements,
  recordPosSettlement,
  writePosSettlementSelectionSeed,
  POS_SETTLEMENT_SELECTION_SEED_STORAGE_KEY,
  type OutstandingReceivableOverview,
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
    getOutstandingReceivables: vi.fn(),
    getRecentPosSettlements: vi.fn(),
    recordPosSettlement: vi.fn(),
  };
});

const getOutstandingReceivablesMock = vi.mocked(getOutstandingReceivables);
const getRecentPosSettlementsMock = vi.mocked(getRecentPosSettlements);
const recordPosSettlementMock = vi.mocked(recordPosSettlement);
const confirmedDateFormatter = new Intl.DateTimeFormat("ko-KR", {
  timeZone: "Asia/Seoul",
  dateStyle: "medium",
  timeStyle: "short",
});

const firstReceivable = {
  mealUsageId: "00000000-0000-0000-0000-000000000001",
  mealContractId: "00000000-0000-0000-0000-000000000011",
  partnerDisplayName: "협력사 A",
  confirmedAt: "2026-08-12T01:00:00Z",
  receivableCreatedMinor: 1_000,
};

const secondReceivable = {
  ...firstReceivable,
  mealUsageId: "00000000-0000-0000-0000-000000000002",
  partnerDisplayName: "협력사 B",
  receivableCreatedMinor: 2_000,
};

const otherContractReceivable = {
  ...firstReceivable,
  mealUsageId: "00000000-0000-0000-0000-000000000003",
  mealContractId: "00000000-0000-0000-0000-000000000012",
  partnerDisplayName: "협력사 C",
  receivableCreatedMinor: 3_000,
};

const savedSettlement: PosSettlement = {
  posBusinessDate: "2026-08-11",
  submittedTotalMinor: 1_000,
  recordedAt: "2026-08-12T02:00:00Z",
  allocations: [{
    partnerDisplayName: firstReceivable.partnerDisplayName,
    confirmedAt: firstReceivable.confirmedAt,
    receivableAmountMinor: 1_000,
  }],
};

const savedHistorySettlement: PosSettlement = {
  ...savedSettlement,
  submittedTotalMinor: 3_000,
  allocations: [
    {
      partnerDisplayName: firstReceivable.partnerDisplayName,
      confirmedAt: firstReceivable.confirmedAt,
      receivableAmountMinor: 1_000,
    },
    {
      partnerDisplayName: secondReceivable.partnerDisplayName,
      confirmedAt: secondReceivable.confirmedAt,
      receivableAmountMinor: 2_000,
    },
  ],
};

function historyPage(items: PosSettlement[]): PosSettlementHistoryPage {
  return { items, page: 0, size: 20, hasNext: false };
}

function receivableOverview(
  items: typeof firstReceivable[],
  partnerOrganizationIds: Map<string, string | null> = new Map(),
): OutstandingReceivableOverview {
  const byMealContractId = new Map<string, typeof firstReceivable[]>();
  for (const item of items) {
    const candidates = byMealContractId.get(item.mealContractId) ?? [];
    candidates.push(item);
    byMealContractId.set(item.mealContractId, candidates);
  }
  return {
    items,
    partners: Array.from(byMealContractId, ([mealContractId, candidates]) => ({
      mealContractId,
      partnerOrganizationId: partnerOrganizationIds.get(mealContractId) ?? null,
      partnerDisplayName: candidates[0]?.partnerDisplayName ?? null,
      previousPosBusinessDate: "2026-08-11",
      periodConfirmedUsageTotalMinor: candidates.reduce(
        (total, candidate) => total + candidate.receivableCreatedMinor,
        0,
      ),
      periodPrepaidAppliedTotalMinor: 0,
      outstandingReceivableCount: candidates.length,
      outstandingReceivableTotalMinor: candidates.reduce(
        (total, candidate) => total + candidate.receivableCreatedMinor,
        0,
      ),
    })),
  };
}

function selectionLabel(receivable: typeof firstReceivable): string {
  return (receivable.partnerDisplayName ?? "협력사 정보 없음")
    + " "
    + confirmedDateFormatter.format(new Date(receivable.confirmedAt))
    + " 선택";
}

function deferred<T>() {
  let resolve: (value: T) => void = () => undefined;
  let reject: (reason?: unknown) => void = () => undefined;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, reject, resolve };
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.resetAllMocks();
});

describe("PosSettlementForm", () => {
  it("reconstructs a monthly handoff from the fresh receivable overview and ignores seeded amount/contract", async () => {
    getOutstandingReceivablesMock.mockResolvedValue(receivableOverview([firstReceivable]));
    getRecentPosSettlementsMock.mockResolvedValue(historyPage([]));
    expect(writePosSettlementSelectionSeed({
      mealUsageIds: [firstReceivable.mealUsageId],
      mealContractId: "00000000-0000-0000-0000-000000000099",
      amountMinor: 99_999,
    })).toBe(true);

    render(<PosSettlementForm />);

    const checkbox = await screen.findByRole("checkbox", { name: selectionLabel(firstReceivable) });
    expect(checkbox).toBeChecked();
    expect(screen.getByLabelText("결제 금액")).toHaveValue(1_000);
    expect(window.sessionStorage.getItem(POS_SETTLEMENT_SELECTION_SEED_STORAGE_KEY)).toBeNull();
  });

  it("groups shared partner contracts while keeping legacy contracts independent and selection contract-scoped", async () => {
    const user = userEvent.setup();
    const sharedContractReceivable = {
      ...otherContractReceivable,
      mealUsageId: "00000000-0000-0000-0000-000000000004",
      mealContractId: "00000000-0000-0000-0000-000000000013",
      partnerDisplayName: "협력사 A",
      confirmedAt: "2026-08-12T02:00:00Z",
    };
    const legacyContractReceivable = {
      ...otherContractReceivable,
      mealUsageId: "00000000-0000-0000-0000-000000000005",
      mealContractId: "00000000-0000-0000-0000-000000000014",
      partnerDisplayName: "협력사 legacy",
    };
    const sharedPartnerOrganizationId = "00000000-0000-0000-0000-000000000010";
    getOutstandingReceivablesMock.mockResolvedValue(receivableOverview(
      [firstReceivable, sharedContractReceivable, legacyContractReceivable],
      new Map([
        [firstReceivable.mealContractId, sharedPartnerOrganizationId],
        [sharedContractReceivable.mealContractId, sharedPartnerOrganizationId],
      ]),
    ));
    getRecentPosSettlementsMock.mockResolvedValue(historyPage([]));

    render(<PosSettlementForm />);

    const firstCheckbox = await screen.findByRole("checkbox", { name: selectionLabel(firstReceivable) });
    const sharedPartnerGroup = screen.getByRole("heading", { name: "협력사 A", level: 3 }).closest("section");
    expect(sharedPartnerGroup).not.toBeNull();
    expect(within(sharedPartnerGroup as HTMLElement).getAllByRole("heading", { level: 4 })).toHaveLength(2);
    const legacyPartnerGroup = screen.getByRole("heading", { name: "협력사 legacy", level: 3 }).closest("section");
    expect(legacyPartnerGroup).not.toBeNull();
    expect(within(legacyPartnerGroup as HTMLElement).getAllByRole("heading", { level: 4 })).toHaveLength(1);

    const sharedContractCheckbox = screen.getByRole("checkbox", { name: selectionLabel(sharedContractReceivable) });
    expect(sharedContractCheckbox).toBeEnabled();
    await user.click(firstCheckbox);
    expect(sharedContractCheckbox).toBeDisabled();
  });

  it("requires explicit same-contract selection and a typed settlement total, then refreshes saved history from the API", async () => {
    const user = userEvent.setup();
    getOutstandingReceivablesMock.mockResolvedValue(receivableOverview([
      firstReceivable,
      secondReceivable,
      otherContractReceivable,
    ]));
    getRecentPosSettlementsMock
      .mockResolvedValueOnce(historyPage([]))
      .mockResolvedValueOnce(historyPage([savedSettlement]));
    recordPosSettlementMock.mockResolvedValue(savedSettlement);
    vi.stubGlobal("crypto", { randomUUID: vi.fn().mockReturnValue("00000000-0000-0000-0000-000000000200") });

    render(<PosSettlementForm />);

    const firstCheckbox = await screen.findByRole("checkbox", { name: selectionLabel(firstReceivable) });
    await user.click(firstCheckbox);
    expect(screen.getByText("선택한 결제할 금액").parentElement).toHaveTextContent("₩1,000");
    expect(screen.getByRole("heading", { name: "결제할 금액", level: 1 })).toBeVisible();
    expect(screen.getAllByText(/마지막 결제일:/).length).toBeGreaterThan(0);
    expect(screen.getAllByText("마지막 결제일 이후 사용 금액").length).toBeGreaterThan(0);
    expect(screen.getAllByText("선불로 처리된 금액").length).toBeGreaterThan(0);
    expect(screen.getAllByText("결제할 금액").length).toBeGreaterThan(1);
    expect(screen.getAllByText("남은 금액").length).toBeGreaterThan(0);
    expect(screen.queryByText(/모든 달에서 아직 남아 있는 금액/)).not.toBeInTheDocument();
    expect(screen.queryByText(/결제 완료\(선불\):/)).not.toBeInTheDocument();
    expect(screen.getAllByText("선불 잔액으로 처리되어 추가 결제할 금액 없음").length).toBeGreaterThan(0);
    const secondCheckbox = screen.getByRole("checkbox", { name: selectionLabel(secondReceivable) });
    await user.click(secondCheckbox);
    expect(screen.getByText("선택한 결제할 금액").parentElement).toHaveTextContent("₩3,000");
    await user.click(secondCheckbox);
    expect(screen.getByText("선택한 결제할 금액").parentElement).toHaveTextContent("₩1,000");
    expect(screen.getByRole("checkbox", { name: selectionLabel(otherContractReceivable) })).toBeDisabled();
    expect(screen.queryByText("계약 " + firstReceivable.mealContractId)).not.toBeInTheDocument();
    expect(screen.queryByText("계약 " + otherContractReceivable.mealContractId)).not.toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("결제일"), { target: { value: "2026-08-11" } });
    fireEvent.change(screen.getByLabelText("결제 금액"), { target: { value: "1000" } });
    await user.click(screen.getByRole("button", { name: "선택한 결제할 금액 기록하기" }));

    await waitFor(() => expect(recordPosSettlementMock).toHaveBeenCalledWith({
      mealContractId: firstReceivable.mealContractId,
      posBusinessDate: "2026-08-11",
      submittedTotalMinor: 1_000,
      mealUsageIds: [firstReceivable.mealUsageId],
    }, "00000000-0000-0000-0000-000000000200"));
    expect(await screen.findByRole("status")).toHaveTextContent("결제 기록 저장 완료");
    expect(screen.getByRole("status")).not.toHaveTextContent("직원이 입력한 결제 기록을 저장했습니다.");
    expect(screen.getByRole("list", { name: "결제 기록에 포함된 금액" })).toHaveTextContent(firstReceivable.partnerDisplayName);
    expect(screen.getByRole("list", { name: "결제 기록에 포함된 금액" })).toHaveTextContent("₩1,000");
    expect(screen.getByRole("list", { name: "결제 기록에 포함된 금액" })).not.toHaveTextContent(firstReceivable.mealUsageId);
    await waitFor(() => expect(getRecentPosSettlementsMock).toHaveBeenCalledTimes(2));
    await user.click(await screen.findByRole("button", {
      name: "결제일 " + savedSettlement.posBusinessDate + " 상세 보기",
    }));
    expect(await screen.findByRole("list", {
      name: "결제일 " + savedSettlement.posBusinessDate + " 결제 기록에 포함된 금액",
    })).toHaveTextContent(firstReceivable.partnerDisplayName);
  });

  it("does not replace a staff-entered settlement total with the derived total", async () => {
    const user = userEvent.setup();
    getOutstandingReceivablesMock.mockResolvedValue(receivableOverview([firstReceivable]));
    getRecentPosSettlementsMock.mockResolvedValue(historyPage([]));
    render(<PosSettlementForm />);

    await user.click(await screen.findByRole("checkbox", { name: selectionLabel(firstReceivable) }));
    fireEvent.change(screen.getByLabelText("결제일"), { target: { value: "2026-08-11" } });
    fireEvent.change(screen.getByLabelText("결제 금액"), { target: { value: "999" } });
    await user.click(screen.getByRole("button", { name: "선택한 결제할 금액 기록하기" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("결제 금액과 선택한 결제할 금액이 다릅니다.");
    expect(recordPosSettlementMock).not.toHaveBeenCalled();
    expect(screen.getByLabelText("결제 금액")).toHaveValue(999);
  });

  it("keeps one idempotency key for an ambiguous network retry until staff changes the input", async () => {
    const user = userEvent.setup();
    getOutstandingReceivablesMock.mockResolvedValue(receivableOverview([firstReceivable]));
    getRecentPosSettlementsMock.mockResolvedValue(historyPage([]));
    recordPosSettlementMock
      .mockRejectedValueOnce(new TypeError("network failed"))
      .mockRejectedValueOnce(new TypeError("network failed"));
    vi.stubGlobal("crypto", { randomUUID: vi.fn().mockReturnValue("00000000-0000-0000-0000-000000000201") });
    render(<PosSettlementForm />);

    await user.click(await screen.findByRole("checkbox", { name: selectionLabel(firstReceivable) }));
    fireEvent.change(screen.getByLabelText("결제일"), { target: { value: "2026-08-11" } });
    fireEvent.change(screen.getByLabelText("결제 금액"), { target: { value: "1000" } });
    const submit = screen.getByRole("button", { name: "선택한 결제할 금액 기록하기" });
    await user.click(submit);
    await screen.findByRole("alert");
    await user.click(submit);

    await waitFor(() => expect(recordPosSettlementMock).toHaveBeenCalledTimes(2));
    expect(recordPosSettlementMock.mock.calls.map((call) => call[1])).toEqual([
      "00000000-0000-0000-0000-000000000201",
      "00000000-0000-0000-0000-000000000201",
    ]);
  });

  it("renders saved history and all immutable allocations even when there are no outstanding receivables", async () => {
    const user = userEvent.setup();
    getOutstandingReceivablesMock.mockResolvedValue(receivableOverview([]));
    getRecentPosSettlementsMock.mockResolvedValue(historyPage([savedHistorySettlement]));

    render(<PosSettlementForm />);

    expect(await screen.findByText("결제할 금액 없음")).toBeVisible();
    await user.click(await screen.findByRole("button", {
      name: "결제일 " + savedHistorySettlement.posBusinessDate + " 상세 보기",
    }));
    expect(screen.queryByText("직원이 입력해 저장한 결제 기록입니다.")).not.toBeInTheDocument();
    const allocationList = await screen.findByRole("list", {
      name: "결제일 " + savedHistorySettlement.posBusinessDate + " 결제 기록에 포함된 금액",
    });
    expect(allocationList).toHaveTextContent(firstReceivable.partnerDisplayName);
    expect(allocationList).toHaveTextContent(secondReceivable.partnerDisplayName);
    expect(allocationList).not.toHaveTextContent(firstReceivable.mealUsageId);
    expect(allocationList).not.toHaveTextContent(secondReceivable.mealUsageId);
    expect(allocationList).toHaveTextContent("₩1,000");
    expect(allocationList).toHaveTextContent("₩2,000");
    expect(screen.getByText("결제 금액")).toBeVisible();
    expect(screen.queryByText("기록 계정")).not.toBeInTheDocument();
    expect(screen.queryByText("store-hk")).not.toBeInTheDocument();
  });

  it("retries history independently after a history-only failure", async () => {
    const user = userEvent.setup();
    getOutstandingReceivablesMock.mockResolvedValue(receivableOverview([firstReceivable]));
    getRecentPosSettlementsMock
      .mockRejectedValueOnce(new ApiError(500, "INTERNAL_SERVER_ERROR"))
      .mockResolvedValueOnce(historyPage([savedHistorySettlement]));

    render(<PosSettlementForm />);

    expect(await screen.findByRole("checkbox", { name: selectionLabel(firstReceivable) })).toBeVisible();
    expect(await screen.findByRole("alert")).toHaveTextContent("최근 결제 기록을 불러오지 못했습니다.");
    await user.click(screen.getByRole("button", { name: "결제 기록 다시 불러오기" }));

    await user.click(await screen.findByRole("button", {
      name: "결제일 " + savedHistorySettlement.posBusinessDate + " 상세 보기",
    }));
    expect(await screen.findByRole("list", {
      name: "결제일 " + savedHistorySettlement.posBusinessDate + " 결제 기록에 포함된 금액",
    })).toHaveTextContent(secondReceivable.partnerDisplayName);
    expect(getOutstandingReceivablesMock).toHaveBeenCalledTimes(1);
    expect(getRecentPosSettlementsMock).toHaveBeenCalledTimes(2);
  });

  it("loads saved history again after a browser reload instead of reconstructing it from the form", async () => {
    getOutstandingReceivablesMock.mockResolvedValue(receivableOverview([]));
    getRecentPosSettlementsMock.mockResolvedValue(historyPage([savedHistorySettlement]));
    const firstRender = render(<PosSettlementForm />);

    await screen.findByRole("button", {
      name: "결제일 " + savedHistorySettlement.posBusinessDate + " 상세 보기",
    });
    firstRender.unmount();
    render(<PosSettlementForm />);

    expect(await screen.findByRole("button", {
      name: "결제일 " + savedHistorySettlement.posBusinessDate + " 상세 보기",
    })).toBeVisible();
    expect(getRecentPosSettlementsMock).toHaveBeenCalledTimes(2);
  });

  it("clears sensitive rows and returns to login when the server says the session is unauthenticated", async () => {
    getOutstandingReceivablesMock.mockRejectedValue(new ApiError(401, "AUTHENTICATION_REQUIRED"));
    getRecentPosSettlementsMock.mockResolvedValue(historyPage([savedHistorySettlement]));
    render(<PosSettlementForm />);

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/store/login?next=/store/pos-settlements"));
    expect(screen.queryByText("협력사 A")).not.toBeInTheDocument();
    expect(screen.queryByText(savedHistorySettlement.posBusinessDate)).not.toBeInTheDocument();
  });

  it("clears both panels on a forbidden history response", async () => {
    const pendingHistory = deferred<PosSettlementHistoryPage>();
    getOutstandingReceivablesMock.mockResolvedValue(receivableOverview([firstReceivable]));
    getRecentPosSettlementsMock.mockImplementation(() => pendingHistory.promise);
    render(<PosSettlementForm />);

    expect(await screen.findByRole("checkbox", { name: selectionLabel(firstReceivable) })).toBeVisible();
    pendingHistory.reject(new ApiError(403, "ACCESS_DENIED"));

    expect(await screen.findByText("접근 권한이 없습니다")).toBeVisible();
    expect(screen.queryByText("협력사 A")).not.toBeInTheDocument();
    expect(screen.queryByText(savedHistorySettlement.posBusinessDate)).not.toBeInTheDocument();
  });
});
