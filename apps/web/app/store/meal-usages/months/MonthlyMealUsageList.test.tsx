import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/store-api";
import { getMonthlyMealUsages } from "@/lib/monthly-meal-usage-api";
import { MonthlyMealUsageList } from "./MonthlyMealUsageList";

const replace = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace }),
}));

vi.mock("@/lib/monthly-meal-usage-api", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/lib/monthly-meal-usage-api")>();
  return { ...original, getMonthlyMealUsages: vi.fn() };
});

const getMonthlyMealUsagesMock = vi.mocked(getMonthlyMealUsages);

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

function page(overrides: Partial<Awaited<ReturnType<typeof getMonthlyMealUsages>>> = {}) {
  return {
    month: "2026-08",
    fromMonth: "2026-08",
    toMonth: "2026-08",
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
  vi.resetAllMocks();
});

describe("MonthlyMealUsageList", () => {
  it("renders confirmed rows without input-source labels, ordinary navigation, and no automatic polling", async () => {
    getMonthlyMealUsagesMock.mockResolvedValueOnce(page()).mockResolvedValueOnce(page({ page: 1, hasNext: false }));
    render(<MonthlyMealUsageList />);

    expect(await screen.findByText("월별 장부")).toBeVisible();
    expect(screen.getByText("확인자 HK")).toBeVisible();
    expect(screen.getByText("결제할 금액")).toBeVisible();
    expect(screen.getByText("협력사 A")).toBeVisible();
    expect(screen.getByText("조회 기간 합계")).toBeVisible();
    expect(screen.getByLabelText("조회 기간 합계")).toHaveTextContent("₩12,000");
    expect(screen.getByText("이름 미입력")).toBeVisible();
    expect(screen.getByRole("link", { name: "결제할 금액 보기" })).toHaveAttribute("href", "/store/pos-settlements");
    expect(screen.queryByText("모바일 QR 입력")).not.toBeInTheDocument();
    expect(screen.queryByText("매장 태블릿 입력")).not.toBeInTheDocument();
    expect(screen.queryByText("거절")).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "확인 대기로 이동" })).toHaveAttribute("href", "/store/meal-usages");
    expect(getMonthlyMealUsagesMock).toHaveBeenCalledTimes(1);
    expect(getMonthlyMealUsagesMock).toHaveBeenCalledWith(expect.stringMatching(/^\d{4}-(0[1-9]|1[0-2])$/), expect.stringMatching(/^\d{4}-(0[1-9]|1[0-2])$/), 0, 20);

    await act(async () => {
      await new Promise((resolve) => window.setTimeout(resolve, 0));
    });
    expect(getMonthlyMealUsagesMock).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole("button", { name: "다음 페이지" }));
    await waitFor(() => expect(getMonthlyMealUsagesMock).toHaveBeenLastCalledWith(expect.any(String), expect.any(String), 1, 20));
    expect(screen.getByText("2페이지")).toBeVisible();
  });

  it("clears sensitive rows and sends only the allowlisted monthly next path after 401", async () => {
    getMonthlyMealUsagesMock.mockRejectedValue(new ApiError(401, "AUTHENTICATION_REQUIRED"));
    render(<MonthlyMealUsageList />);

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/store/login?next=/store/meal-usages/months"));
    expect(screen.queryByText("협력사 A")).not.toBeInTheDocument();
  });

  it("clears sensitive rows and shows denied state after 403", async () => {
    getMonthlyMealUsagesMock.mockRejectedValue(new ApiError(403, "ACCESS_DENIED"));
    render(<MonthlyMealUsageList />);

    expect(await screen.findByText("접근 권한이 없습니다")).toBeVisible();
    expect(screen.queryByText("협력사 A")).not.toBeInTheDocument();
  });

  it("retains the last safe rows when a manual reload fails and permits later retry", async () => {
    getMonthlyMealUsagesMock
      .mockResolvedValueOnce(page({ hasNext: false }))
      .mockRejectedValueOnce(new Error("network unavailable"))
      .mockResolvedValueOnce(page({ items: [], hasNext: false }));
    render(<MonthlyMealUsageList />);

    expect(await screen.findByText("협력사 A")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "다시 불러오기" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("월별 장부를 불러오지 못했습니다. 다시 시도해 주세요.");
    expect(screen.getByText("협력사 A")).toBeVisible();

    fireEvent.click(screen.getByRole("button", { name: "다시 불러오기" }));
    expect(await screen.findByText(/식대 내역이 없습니다/)).toBeVisible();
  });

  it("changes month from the native month selector and resets to page zero", async () => {
    getMonthlyMealUsagesMock.mockResolvedValue(page());
    render(<MonthlyMealUsageList />);
    await screen.findByText("협력사 A");

    const monthInput = screen.getByLabelText("시작 월");
    fireEvent.change(monthInput, { target: { value: "2026-07" } });
    await waitFor(() => expect(getMonthlyMealUsagesMock).toHaveBeenLastCalledWith("2026-07", "2026-08", 0, 20));
    fireEvent.change(screen.getByLabelText("종료 월"), { target: { value: "2026-09" } });
    await waitFor(() => expect(getMonthlyMealUsagesMock).toHaveBeenLastCalledWith("2026-07", "2026-09", 0, 20));
    await flushUpdates();
  });

  it("renders each additive settlement status and safely omits an absent status", async () => {
    getMonthlyMealUsagesMock.mockResolvedValue(page({
      items: [
        { ...items[0], id: "00000000-0000-0000-0000-000000000001", settlementStatus: "PAYMENT_DUE" },
        { ...items[0], id: "00000000-0000-0000-0000-000000000002", settlementStatus: "PAYMENT_RECORDED" },
        { ...items[0], id: "00000000-0000-0000-0000-000000000003", settlementStatus: "PREPAID_SETTLED" },
        { ...items[0], id: "00000000-0000-0000-0000-000000000004", settlementStatus: null },
      ],
    }));
    render(<MonthlyMealUsageList />);

    expect(await screen.findByText("결제할 금액")).toBeVisible();
    expect(screen.getByText("결제 기록")).toBeVisible();
    expect(screen.getByText("선불로 처리됨")).toBeVisible();
    expect(screen.getAllByText("확인자 HK")).toHaveLength(4);
    expect(screen.getAllByText("결제할 금액")).toHaveLength(1);
  });
});
