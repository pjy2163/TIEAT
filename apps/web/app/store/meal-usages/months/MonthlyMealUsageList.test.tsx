import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/store-api";
import { getConfirmedMealUsages } from "@/lib/monthly-meal-usage-api";
import { getOutstandingReceivables, POS_SETTLEMENT_SELECTION_SEED_STORAGE_KEY } from "@/lib/pos-settlement-api";
import { MonthlyMealUsageList } from "./MonthlyMealUsageList";

const replace = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace }),
}));

vi.mock("@/lib/monthly-meal-usage-api", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/lib/monthly-meal-usage-api")>();
  return { ...original, getConfirmedMealUsages: vi.fn() };
});

vi.mock("@/lib/pos-settlement-api", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/lib/pos-settlement-api")>();
  return { ...original, getOutstandingReceivables: vi.fn() };
});

const getConfirmedMealUsagesMock = vi.mocked(getConfirmedMealUsages);
const getOutstandingReceivablesMock = vi.mocked(getOutstandingReceivables);

function currentKoreanDateForTest(): string {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts();
  const year = parts.find((part) => part.type === "year")?.value;
  const month = parts.find((part) => part.type === "month")?.value;
  const day = parts.find((part) => part.type === "day")?.value;
  return year && month && day ? `${year}-${month}-${day}` : "2026-08-20";
}

const testToday = currentKoreanDateForTest();

const items = [
  {
    id: "00000000-0000-0000-0000-000000000001",
    status: "CONFIRMED" as const,
    partnerDisplayName: "협력사 A",
    amountMinor: 12_000,
    createdAt: "2026-08-05T01:00:00Z",
    confirmedStaffInitials: "HK",
    settlementStatus: "PAYMENT_DUE" as const,
  },
];

function page(overrides: Partial<Awaited<ReturnType<typeof getConfirmedMealUsages>>> = {}) {
  return {
    fromDate: `${testToday.slice(0, 7)}-01`,
    toDate: testToday,
    timeZone: "Asia/Seoul" as const,
    items,
    page: 0,
    size: 20,
    hasNext: true,
    totalAmountMinor: 12_000,
    ...overrides,
  };
}

async function flushUpdates() {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}

afterEach(() => {
  cleanup();
  window.sessionStorage.clear();
  vi.resetAllMocks();
});

describe("MonthlyMealUsageList", () => {
  it("renders confirmed rows without input-source labels, ordinary navigation, and no automatic polling", async () => {
    getConfirmedMealUsagesMock.mockResolvedValueOnce(page()).mockResolvedValueOnce(page({ page: 1, hasNext: false }));
    render(<MonthlyMealUsageList />);

    const ledgerTitle = await screen.findByText("전체 장부");
    expect(ledgerTitle).toBeVisible();
    expect(screen.queryByText("월별 장부는 사용 이력입니다. 결제할 금액은 월과 관계없이 따로 확인하세요.")).not.toBeInTheDocument();
    expect(screen.getByText("확인자 HK")).toBeVisible();
    expect(screen.getByText("결제 전")).toBeVisible();
    expect(screen.getByText("협력사 A")).toBeVisible();
    expect(screen.getByText("조회 기간 합계")).toBeVisible();
    expect(screen.getByLabelText("조회 기간 합계")).toHaveTextContent("₩12,000");
    expect(screen.getByText("이름 미입력")).toBeVisible();
    const reloadButton = screen.getByRole("button", { name: "새로고침" });
    expect(reloadButton).toBeVisible();
    expect(reloadButton).toHaveTextContent("새로고침");
    expect(reloadButton).toHaveClass("h-11", "rounded-lg", "px-4");
    expect(reloadButton.querySelector("svg")).not.toBeInTheDocument();
    const settlementLink = screen.getByRole("link", { name: "잔금 보기" });
    const pendingLink = screen.getByRole("link", { name: "확인 대기로 이동" });
    expect(settlementLink).toHaveAttribute("href", "/store/pos-settlements");
    expect(ledgerTitle.parentElement).toContainElement(settlementLink);
    expect(ledgerTitle.parentElement).toContainElement(pendingLink);
    expect(screen.getByLabelText("시작일")).toHaveAttribute("type", "date");
    expect(screen.queryByText("모바일 QR 입력")).not.toBeInTheDocument();
    expect(screen.queryByText("매장 태블릿 입력")).not.toBeInTheDocument();
    expect(screen.queryByText("거절")).not.toBeInTheDocument();
    expect(pendingLink).toHaveAttribute("href", "/store/meal-usages");
    expect(getConfirmedMealUsagesMock).toHaveBeenCalledTimes(1);
    expect(getConfirmedMealUsagesMock).toHaveBeenCalledWith(expect.stringMatching(/^\d{4}-(0[1-9]|1[0-2])-\d{2}$/), expect.stringMatching(/^\d{4}-(0[1-9]|1[0-2])-\d{2}$/), 0, 20);

    await act(async () => {
      await new Promise((resolve) => window.setTimeout(resolve, 0));
    });
    expect(getConfirmedMealUsagesMock).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole("button", { name: "다음 페이지" }));
    await waitFor(() => expect(getConfirmedMealUsagesMock).toHaveBeenLastCalledWith(expect.any(String), expect.any(String), 1, 20));
    expect(screen.getByText("2페이지")).toBeVisible();
  });

  it("keeps amount, confirmer, and settlement status in separate side rows", async () => {
    getConfirmedMealUsagesMock.mockResolvedValue(page({ hasNext: false }));
    render(<MonthlyMealUsageList />);

    const row = (await screen.findByText("협력사 A")).closest("li");
    expect(row).not.toBeNull();
    const scopedRow = within(row as HTMLElement);
    const amount = scopedRow.getByText("₩12,000");
    const confirmer = scopedRow.getByText("확인자 HK");
    const settlementStatus = scopedRow.getByText("결제 전");
    const inputter = scopedRow.getByText("이름 미입력");
    const inputTimeText = new Intl.DateTimeFormat("ko-KR", {
      timeZone: "Asia/Seoul",
      dateStyle: "medium",
      timeStyle: "short",
    }).format(new Date(items[0].createdAt));
    const inputTime = scopedRow.getByText(inputTimeText, { exact: true });

    expect(scopedRow.queryByText("입력자", { exact: true })).not.toBeInTheDocument();
    expect(scopedRow.queryByText("입력 시각", { exact: true })).not.toBeInTheDocument();
    expect(row).toHaveClass("grid-cols-[minmax(0,1fr)_auto]", "gap-x-4", "gap-y-4", "px-5", "py-5", "sm:px-6");
    const side = amount.parentElement as HTMLElement;
    expect(side).toHaveClass("flex", "min-w-0", "flex-col", "items-end", "gap-1", "text-right", "md:min-w-36");
    expect(Array.from(side.children)).toEqual([amount, confirmer, settlementStatus]);
    for (const entry of [amount, confirmer, settlementStatus]) {
      expect(entry.parentElement).toBe(side);
    }
    expect(amount.compareDocumentPosition(confirmer) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(confirmer.compareDocumentPosition(settlementStatus) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(inputter.compareDocumentPosition(inputTime) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(inputTime.compareDocumentPosition(amount) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(inputTime).toHaveClass("mt-2", "break-words");
    for (const className of ["text-sm", "font-medium", "leading-5", "text-[var(--text-secondary)]"]) {
      expect(inputter).toHaveClass(className);
      expect(confirmer).toHaveClass(className);
      expect(inputTime).toHaveClass(className);
    }
  });

  it("clears sensitive rows and sends only the allowlisted monthly next path after 401", async () => {
    getConfirmedMealUsagesMock.mockRejectedValue(new ApiError(401, "AUTHENTICATION_REQUIRED"));
    render(<MonthlyMealUsageList />);

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/store/login?next=/store/meal-usages/months"));
    expect(screen.queryByText("협력사 A")).not.toBeInTheDocument();
  });

  it("clears sensitive rows and shows denied state after 403", async () => {
    getConfirmedMealUsagesMock.mockRejectedValue(new ApiError(403, "ACCESS_DENIED"));
    render(<MonthlyMealUsageList />);

    expect(await screen.findByText("접근 권한이 없습니다")).toBeVisible();
    expect(screen.queryByText("협력사 A")).not.toBeInTheDocument();
  });

  it("retains the last safe rows when a manual reload fails and permits later retry", async () => {
    getConfirmedMealUsagesMock
      .mockResolvedValueOnce(page({ hasNext: false }))
      .mockRejectedValueOnce(new Error("network unavailable"))
      .mockResolvedValueOnce(page({ items: [], hasNext: false }));
    render(<MonthlyMealUsageList />);

    expect(await screen.findByText("협력사 A")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "새로고침" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("전체 장부를 불러오지 못했습니다. 다시 시도해 주세요.");
    expect(screen.getByText("협력사 A")).toBeVisible();

    fireEvent.click(screen.getByRole("button", { name: "새로고침" }));
    expect(await screen.findByText(/식대 내역이 없습니다/)).toBeVisible();
    expect(screen.queryByText("해당 기간에 표시할 식대 사용 내역이 없습니다.")).not.toBeInTheDocument();
  });

  it("changes date range from the native date selectors and resets to page zero", async () => {
    getConfirmedMealUsagesMock.mockResolvedValue(page());
    render(<MonthlyMealUsageList />);
    await screen.findByText("협력사 A");

    fireEvent.change(screen.getByLabelText("시작일"), { target: { value: "2026-07-01" } });
    await waitFor(() => expect(getConfirmedMealUsagesMock).toHaveBeenLastCalledWith("2026-07-01", expect.any(String), 0, 20));
    fireEvent.change(screen.getByLabelText("종료일"), { target: { value: "2026-09-30" } });
    await waitFor(() => expect(getConfirmedMealUsagesMock).toHaveBeenLastCalledWith("2026-07-01", "2026-09-30", 0, 20));
    await flushUpdates();
  });

  it("renders each additive settlement status and safely omits an absent status", async () => {
    getConfirmedMealUsagesMock.mockResolvedValue(page({
      items: [
        { ...items[0], id: "00000000-0000-0000-0000-000000000001", settlementStatus: "PAYMENT_DUE" },
        { ...items[0], id: "00000000-0000-0000-0000-000000000002", settlementStatus: "PAYMENT_RECORDED" },
        { ...items[0], id: "00000000-0000-0000-0000-000000000003", settlementStatus: "PREPAID_SETTLED" },
        { ...items[0], id: "00000000-0000-0000-0000-000000000004", settlementStatus: null },
      ],
    }));
    render(<MonthlyMealUsageList />);

    expect(await screen.findByText("결제 전")).toBeVisible();
    expect(screen.getByText("결제 완료")).toBeVisible();
    expect(screen.getByText("결제 완료(선불)")).toBeVisible();
    expect(screen.getByText("직원이 입력한 POS 정산 내역이 저장된 상태")).toBeInTheDocument();
    expect(screen.getByText("선불 잔액으로 처리되어 추가 결제할 금액 없음")).toBeInTheDocument();
    expect(screen.getAllByText("확인자 HK")).toHaveLength(4);
    expect(screen.getAllByText("결제 전")).toHaveLength(1);
  });

  it("selects only current-page payment-due rows and hands off the authoritative receivable amount", async () => {
    const authoritativeAmount = 7_500;
    getConfirmedMealUsagesMock.mockResolvedValue(page({
      items: [
        items[0],
        { ...items[0], id: "00000000-0000-0000-0000-000000000002", settlementStatus: "PAYMENT_RECORDED" },
      ],
      hasNext: false,
    }));
    getOutstandingReceivablesMock.mockResolvedValue({
      items: [{
        mealUsageId: items[0].id,
        mealContractId: "00000000-0000-0000-0000-000000000011",
        partnerDisplayName: items[0].partnerDisplayName,
        confirmedAt: items[0].createdAt,
        receivableCreatedMinor: authoritativeAmount,
      }],
      partners: [{
        mealContractId: "00000000-0000-0000-0000-000000000011",
        partnerDisplayName: items[0].partnerDisplayName,
        previousPosBusinessDate: null,
        periodConfirmedUsageTotalMinor: authoritativeAmount,
        periodPrepaidAppliedTotalMinor: 0,
        outstandingReceivableCount: 1,
        outstandingReceivableTotalMinor: authoritativeAmount,
      }],
    });
    render(<MonthlyMealUsageList />);

    const dueSelection = await screen.findByRole("checkbox", { name: /협력사 A .* 선택/ });
    expect(dueSelection).toBeVisible();
    expect(screen.getByRole("button", { name: "전체 선택 (현재 페이지 2건)" })).toBeVisible();
    expect(screen.getByRole("button", { name: "미결제 모두 선택 (현재 페이지 1건)" })).toBeVisible();

    fireEvent.click(dueSelection);
    expect(await screen.findByText("1건 선택")).toBeVisible();
    expect(await screen.findByText("결제할 금액 ₩7,500")).toBeVisible();
    expect(screen.getByRole("link", { name: "선택한 결제할 금액 기록하기" })).toBeVisible();
    expect(getOutstandingReceivablesMock).toHaveBeenCalledTimes(1);
    expect(screen.getAllByRole("checkbox")).toHaveLength(1);

    const handoff = screen.getByRole("link", { name: "선택한 결제할 금액 기록하기" });
    fireEvent.click(handoff);
    expect(window.sessionStorage.getItem(POS_SETTLEMENT_SELECTION_SEED_STORAGE_KEY)).toContain(items[0].id);
    expect(window.sessionStorage.getItem(POS_SETTLEMENT_SELECTION_SEED_STORAGE_KEY)).toContain(String(authoritativeAmount));
  });
});
