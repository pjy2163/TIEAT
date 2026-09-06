import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/store-api";
import {
  getStorePartners,
  getStoreProfile,
  type StorePartner,
} from "@/lib/store-partner-api";
import { getConfirmedMealUsages, type ConfirmedMealUsagePage, type MonthlyMealUsage } from "@/lib/monthly-meal-usage-api";
import { StorePartnerProvider, useStorePartnerContext } from "./StorePartnerContext";
import { MonthlyMealUsageList } from "./meal-usages/months/MonthlyMealUsageList";
import { StoreWorkspaceShell } from "./StoreWorkspaceShell";

const navigation = vi.hoisted(() => ({
  pathname: "/store/meal-usages/months",
  replace: vi.fn(),
}));

const partnerMocks = vi.hoisted(() => ({
  getStorePartners: vi.fn(),
  getStoreProfile: vi.fn(),
  getConfirmedMealUsages: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  usePathname: () => navigation.pathname,
  useRouter: () => navigation,
  useSearchParams: () => new URLSearchParams(window.location.search),
}));

vi.mock("@/lib/store-partner-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/store-partner-api")>();
  return {
    ...actual,
    getStorePartners: partnerMocks.getStorePartners,
    getStoreProfile: partnerMocks.getStoreProfile,
  };
});

vi.mock("@/lib/monthly-meal-usage-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/monthly-meal-usage-api")>();
  return { ...actual, getConfirmedMealUsages: partnerMocks.getConfirmedMealUsages };
});

const partnerA: StorePartner = {
  mealContractId: "11111111-1111-4111-8111-111111111111",
  partnerOrganizationId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  partnerDisplayName: "가나다 협력사",
  partnerKind: "ORGANIZATION",
  paymentType: "POSTPAID",
  qrSelectable: true,
  representativePhone: "010-1234-5678",
  representativeEmail: "owner@example.com",
};
const partnerASecondContract: StorePartner = {
  ...partnerA,
  mealContractId: "11111111-1111-4111-8111-111111111112",
};
const partnerB: StorePartner = {
  mealContractId: "22222222-2222-4222-8222-222222222222",
  partnerOrganizationId: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
  partnerDisplayName: "마바사 협력사",
  partnerKind: "INDIVIDUAL",
  paymentType: "PREPAID_WITH_RECEIVABLE_OVERFLOW",
  qrSelectable: false,
  representativePhone: null,
  representativeEmail: null,
};

function mealUsage(id: string, partnerDisplayName: string): MonthlyMealUsage {
  return {
    id,
    mealContractId: partnerDisplayName === "가나다 협력사 장부" ? partnerA.mealContractId : null,
    status: "CONFIRMED",
    partnerDisplayName,
    amountMinor: 1000,
    createdAt: "2026-08-02T09:00:00Z",
    confirmedStaffInitials: "HK",
    settlementStatus: "PAYMENT_RECORDED",
  };
}

function mealPage(items: MonthlyMealUsage[], page = 0, hasNext = false): ConfirmedMealUsagePage {
  return {
    fromDate: `${currentKoreanDateForTest().slice(0, 7)}-01`,
    toDate: currentKoreanDateForTest(),
    timeZone: "Asia/Seoul",
    items,
    page,
    size: 20,
    hasNext,
    totalAmountMinor: items.reduce((total, item) => total + item.amountMinor, 0),
  };
}

function currentKoreanDateForTest(): string {
  const parts = new Intl.DateTimeFormat("en-US", {
    day: "2-digit",
    month: "2-digit",
    timeZone: "Asia/Seoul",
    year: "numeric",
  }).formatToParts();
  const year = parts.find((part) => part.type === "year")?.value;
  const month = parts.find((part) => part.type === "month")?.value;
  const day = parts.find((part) => part.type === "day")?.value;
  return `${year}-${month}-${day}`;
}

function setUrl(pathname: string, search = "") {
  navigation.pathname = pathname;
  window.history.replaceState({}, "", `${pathname}${search}`);
}

function Workspace({ children }: Readonly<{ children: React.ReactNode }>) {
  return <StorePartnerProvider>{children}</StorePartnerProvider>;
}

beforeEach(() => {
  vi.clearAllMocks();
  setUrl("/store/meal-usages/months");
  navigation.replace.mockImplementation(() => undefined);
  partnerMocks.getStorePartners.mockResolvedValue([partnerA, partnerASecondContract, partnerB]);
  partnerMocks.getStoreProfile.mockResolvedValue({ loginId: "store-hk", storeDisplayName: null });
  partnerMocks.getConfirmedMealUsages.mockResolvedValue(mealPage([mealUsage("usage-1", "전체 장부 항목")]));
});

afterEach(() => {
  cleanup();
});

describe("R-032 store partner workspace", () => {
  it("keeps the current workspace route when an unauthenticated visitor is sent to login", async () => {
    setUrl("/store/pos-settlements");
    partnerMocks.getStorePartners.mockRejectedValue(new ApiError(401, "AUTHENTICATION_REQUIRED"));
    partnerMocks.getStoreProfile.mockRejectedValue(new ApiError(401, "AUTHENTICATION_REQUIRED"));

    render(<Workspace><p>업무 화면</p></Workspace>);

    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/store/login?next=/store/pos-settlements"));
    expect(navigation.replace).not.toHaveBeenCalledWith("/store/login?next=/store/meal-usages/months");
  });

  it("shares canonical scope URLs, invokes filtered and unfiltered ledgers, resets page, and hides stale rows", async () => {
    const user = userEvent.setup();
    let releaseStale: ((value: ConfirmedMealUsagePage) => void) | null = null;
    partnerMocks.getConfirmedMealUsages.mockImplementation((_from: string, _to: string, page: number, _size: number, mealContractId?: string) => {
      if (mealContractId) return Promise.resolve(mealPage([mealUsage("filtered-1", "가나다 협력사 장부")]));
      if (page === 1) {
        return new Promise<ConfirmedMealUsagePage>((resolve) => {
          releaseStale = resolve;
        });
      }
      return Promise.resolve(mealPage([mealUsage("all-1", "전체 장부 항목")], 0, true));
    });

    render(<Workspace><MonthlyMealUsageList /></Workspace>);
    await waitFor(() => expect(screen.getByText("전체 장부 항목")).toBeInTheDocument());
    const partnerSelect = screen.getByLabelText("협력사 선택") as HTMLSelectElement;
    expect(Array.from(partnerSelect.options).map((option) => option.textContent)).toEqual([
      "전체",
      "계약 1",
      "계약 2",
      partnerB.partnerDisplayName,
    ]);
    expect(partnerSelect.querySelectorAll("optgroup")).toHaveLength(1);
    expect(partnerSelect.querySelector("optgroup")?.getAttribute("label")).toBe(partnerA.partnerDisplayName);
    expect(partnerSelect.querySelectorAll("option")).toHaveLength(4);
    expect(partnerSelect.querySelector(`option[value="${partnerB.mealContractId}"]`)).toHaveAttribute("aria-label", partnerB.partnerDisplayName);
    expect(screen.queryByRole("option", { name: `${partnerB.partnerDisplayName} · 계약 1` })).not.toBeInTheDocument();
    expect(partnerMocks.getConfirmedMealUsages).toHaveBeenCalledWith(expect.stringMatching(/^\d{4}-(0[1-9]|1[0-2])-01$/), expect.stringMatching(/^\d{4}-(0[1-9]|1[0-2])-\d{2}$/), 0, 20);

    await user.click(screen.getByRole("button", { name: "다음" }));
    await waitFor(() => expect(partnerMocks.getConfirmedMealUsages).toHaveBeenCalledWith(expect.stringMatching(/^\d{4}-(0[1-9]|1[0-2])-01$/), expect.stringMatching(/^\d{4}-(0[1-9]|1[0-2])-\d{2}$/), 1, 20));
    expect(screen.queryByText("전체 장부 항목")).not.toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText("협력사 선택"), partnerA.mealContractId);
    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith(`/store/meal-usages/months?mealContractId=${partnerA.mealContractId}`));
    await waitFor(() => expect(partnerMocks.getConfirmedMealUsages).toHaveBeenCalledWith(expect.stringMatching(/^\d{4}-(0[1-9]|1[0-2])-01$/), expect.stringMatching(/^\d{4}-(0[1-9]|1[0-2])-\d{2}$/), 0, 20, partnerA.mealContractId));
    expect(await screen.findByText("가나다 협력사 장부")).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText("협력사 선택"), partnerB.mealContractId);
    await waitFor(() => expect(screen.getByLabelText("협력사 선택")).toHaveValue(partnerB.mealContractId));
    expect(screen.queryByText(`선택: ${partnerB.partnerDisplayName}`)).not.toBeInTheDocument();
    expect(screen.queryByText(`선택: ${partnerB.partnerDisplayName} · 계약 1`)).not.toBeInTheDocument();

    const resolveStale = releaseStale as ((value: ConfirmedMealUsagePage) => void) | null;
    resolveStale?.(mealPage([mealUsage("late-1", "늦게 도착한 이전 페이지")], 1));
    await waitFor(() => expect(screen.queryByText("늦게 도착한 이전 페이지")).not.toBeInTheDocument());
  });

  it("uses a generic recovery panel for malformed scope and server 404", async () => {
    setUrl("/store/meal-usages/months", "?mealContractId=not-a-uuid");
    render(<Workspace><MonthlyMealUsageList /></Workspace>);
    expect(await screen.findByText("협력사를 찾을 수 없습니다")).toBeInTheDocument();
    expect(screen.getByText("선택한 협력사를 찾을 수 없습니다. 전체 협력사 목록에서 다시 선택해 주세요.")).toBeInTheDocument();

    cleanup();
    setUrl("/store/meal-usages/months", `?mealContractId=${partnerA.mealContractId}`);
    partnerMocks.getConfirmedMealUsages.mockRejectedValue(new ApiError(404, "STORE_PARTNER_NOT_FOUND"));
    render(<Workspace><MonthlyMealUsageList /></Workspace>);
    expect(await screen.findByText("협력사를 찾을 수 없습니다")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "전체 협력사 보기" })).toHaveAttribute("href", "/store/meal-usages/months");
  });

  it("opens the mobile drawer semantically and returns focus after Escape", async () => {
    const user = userEvent.setup();
    Object.defineProperty(HTMLDialogElement.prototype, "showModal", {
      configurable: true,
      value(this: HTMLDialogElement) { this.open = true; },
    });
    Object.defineProperty(HTMLDialogElement.prototype, "close", {
      configurable: true,
      value(this: HTMLDialogElement) {
        this.open = false;
        this.dispatchEvent(new Event("close"));
      },
    });
    setUrl("/store/meal-usages");
    partnerMocks.getStoreProfile.mockResolvedValue({ loginId: "store-hk", storeDisplayName: "등록한 가게" });
    render(<Workspace><StoreWorkspaceShell><p>업무 화면</p></StoreWorkspaceShell></Workspace>);

    expect((await screen.findAllByText("등록한 가게"))).toHaveLength(2);
    expect(screen.getAllByLabelText("TIEAT 매장 홈")).toHaveLength(2);
    const menuButton = screen.getByRole("button", { name: "메뉴" });
    menuButton.focus();
    await user.click(menuButton);
    const dialog = screen.getByRole("dialog", { name: "매장 작업 공간 메뉴" });
    expect(dialog).toHaveAttribute("open");
    const closeButton = within(dialog).getByRole("button", { name: "닫기" });
    expect(closeButton).toHaveAttribute("aria-label", "닫기");
    expect(closeButton).toHaveTextContent("×");
    expect(closeButton).toHaveClass("h-10", "w-10", "text-2xl", "font-normal", "leading-none");
    expect(within(dialog).queryByText("닫기")).not.toBeInTheDocument();
    const paymentHistoryLinks = screen.getAllByRole("link", { name: "결제 내역" });
    expect(paymentHistoryLinks).toHaveLength(2);
    expect(paymentHistoryLinks.every((link) => link.getAttribute("href") === "/store/pos-settlements")).toBe(true);
    expect(screen.queryByRole("link", { name: "전체 협력사" })).not.toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: partnerA.partnerDisplayName })).toHaveLength(4);
    const qrLinks = screen.getAllByRole("link", { name: "QR코드 보기" });
    expect(qrLinks).toHaveLength(2);
    expect(qrLinks.every((link) => link.getAttribute("href") === "/store/qr")).toBe(true);
    expect(screen.queryByRole("link", { name: "← 뒤로가기" })).not.toBeInTheDocument();
    expect(screen.getAllByRole("navigation", { name: "매장 작업 공간 메뉴" }).map((navigationElement) => {
      const links = navigationElement.querySelectorAll("a");
      return links[links.length - 1]?.textContent;
    })).toEqual(["QR코드 보기", "QR코드 보기"]);
    expect(screen.getAllByRole("link", { name: "QR코드 보기" }).every((link) => {
      return link.parentElement?.classList.contains("pb-3") && link.parentElement?.classList.contains("lg:pb-0");
    })).toBe(true);
    expect(screen.getAllByText("등록한 가게").every((element) => element.classList.contains("text-lg") && element.classList.contains("text-left") && element.classList.contains("px-2"))).toBe(true);
    expect(within(screen.getByRole("complementary", { name: "매장 작업 공간" })).getByText("자동으로 기록이 쌓이는 장부")).toBeInTheDocument();
    await waitFor(() => expect(closeButton).toHaveFocus());

    await user.keyboard("{Escape}");
    await waitFor(() => expect(dialog).not.toHaveAttribute("open"));
    await waitFor(() => expect(menuButton).toHaveFocus());

    cleanup();
    setUrl("/store/profile");
    render(<Workspace><StoreWorkspaceShell><p>업무 화면</p></StoreWorkspaceShell></Workspace>);
    const backLink = await screen.findByRole("link", { name: "← 뒤로가기" });
    expect(backLink).toHaveAttribute("href", "/store/meal-usages");
  });

});
