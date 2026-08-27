import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ApiError,
  confirmMealUsage,
  getPendingMealUsages,
  rejectMealUsage,
  UnexpectedConfirmationResponseError,
} from "@/lib/store-api";
import { MealUsageList } from "./MealUsageList";

const replace = vi.fn();
const router = { replace };

vi.mock("next/navigation", () => ({
  useRouter: () => router,
}));

vi.mock("@/lib/store-api", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/lib/store-api")>();
  return { ...original, confirmMealUsage: vi.fn(), getPendingMealUsages: vi.fn(), rejectMealUsage: vi.fn() };
});

const confirmMealUsageMock = vi.mocked(confirmMealUsage);
const getPendingMealUsagesMock = vi.mocked(getPendingMealUsages);
const rejectMealUsageMock = vi.mocked(rejectMealUsage);
const pendingItem = {
  mealUsageId: "00000000-0000-0000-0000-000000000001",
  status: "PENDING" as const,
  entrySource: "STORE_TABLET" as const,
  partnerDisplayName: "협력사 A",
  customerName: null,
  amountMinor: 12000,
  createdAt: "2026-08-05T01:00:00Z",
};

const partnerMobilePendingItem = {
  ...pendingItem,
  mealUsageId: "00000000-0000-0000-0000-000000000002",
  entrySource: "PARTNER_MOBILE" as const,
  partnerDisplayName: "협력사 B",
  customerName: "홍길동",
  amountMinor: 1234567,
};

const changedPendingItem = {
  ...pendingItem,
  amountMinor: 12_001,
};

function deferred<T>() {
  let resolve: (value: T) => void = () => undefined;
  let reject: (error: unknown) => void = () => undefined;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, reject, resolve };
}

async function flushUpdates() {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}

afterEach(() => {
  cleanup();
  Object.defineProperty(document, "hidden", { configurable: true, value: false, writable: true });
  vi.unstubAllGlobals();
  vi.useRealTimers();
  vi.resetAllMocks();
});

describe("MealUsageList", () => {
  it("renders source-specific icons and whole-row buttons with metadata, amount, then status", async () => {
    getPendingMealUsagesMock.mockResolvedValue({ items: [pendingItem, partnerMobilePendingItem], page: 0, size: 50, hasNext: false });

    render(<MealUsageList />);

    expect(await screen.findByText("매장 태블릿 입력")).toBeVisible();
    expect(screen.queryByText("TIEAT STORE")).not.toBeInTheDocument();
    expect(screen.getByText("모바일 QR 입력")).toBeVisible();
    expect(screen.getAllByText("확인 대기", { selector: "span" })).toHaveLength(2);
    expect(screen.getByText("₩12,000")).toBeVisible();
    expect(screen.getByText("₩1,234,567")).toBeVisible();
    expect(screen.getAllByText(/2026\. 8\. 5\./)).toHaveLength(2);
    expect(screen.getByText("협력사 A")).toBeVisible();
    expect(screen.getByText("협력사 B")).toBeVisible();
    expect(screen.queryByText("협력사 · 협력사 A")).not.toBeInTheDocument();

    const [tabletRow, partnerMobileRow] = screen.getAllByRole("listitem");
    const tabletButton = tabletRow.querySelector("button");
    const partnerMobileButton = partnerMobileRow.querySelector("button");
    expect(tabletButton).toHaveClass("min-h-16");
    expect(tabletButton?.children[0]).toHaveTextContent("매장 태블릿 입력");
    expect(tabletButton?.children[0]).toHaveTextContent(/2026\. 8\. 5\./);
    expect(tabletButton?.children[1]).toHaveTextContent("₩12,000");
    expect(tabletButton?.children[2]).toHaveTextContent("확인 대기");
    expect(partnerMobileButton?.children[1]).toHaveTextContent("₩1,234,567");

    const tabletIcon = tabletRow.querySelector('svg[data-entry-source-icon="STORE_TABLET"]');
    const partnerMobileIcon = partnerMobileRow.querySelector('svg[data-entry-source-icon="PARTNER_MOBILE"]');
    expect(tabletIcon).toHaveAttribute("aria-hidden", "true");
    expect(tabletIcon).toHaveAttribute("width", "24");
    expect(tabletIcon).toHaveAttribute("height", "24");
    expect(tabletIcon).toHaveClass("text-[#315efb]");
    expect(partnerMobileIcon).toHaveAttribute("aria-hidden", "true");
    expect(partnerMobileIcon).toHaveAttribute("width", "24");
    expect(partnerMobileIcon).toHaveAttribute("height", "24");
    expect(partnerMobileIcon).toHaveClass("text-[#6558d3]");
  });

  it("renders an empty state", async () => {
    getPendingMealUsagesMock.mockResolvedValue({ items: [], page: 0, size: 50, hasNext: false });

    render(<MealUsageList />);

    expect(await screen.findByText("확인 대기가 없습니다")).toBeVisible();
    const pendingTitle = screen.getByRole("heading", { level: 1, name: "확인 대기" });
    const ledgerLink = screen.getByRole("link", { name: "전체 장부" });
    expect(pendingTitle).toBeVisible();
    expect(ledgerLink).toHaveAttribute("href", "/store/meal-usages/months");
    expect(pendingTitle.parentElement).toContainElement(ledgerLink);
    expect(screen.queryByRole("link", { name: "월별 장부" })).not.toBeInTheDocument();
    expect(screen.queryByText("오래된 거래부터 표시합니다.")).not.toBeInTheDocument();
    expect(screen.queryByText("새 거래가 생기면 이 목록에서 확인할 수 있습니다.")).not.toBeInTheDocument();
  });

  it("places confirm and reject icon buttons beside the confirmer initials input", async () => {
    const user = userEvent.setup();
    getPendingMealUsagesMock.mockResolvedValue({ items: [pendingItem], page: 0, size: 50, hasNext: false });

    render(<MealUsageList />);

    await user.click(await screen.findByRole("button", { name: /매장 태블릿 입력/ }));
    const initialsInput = screen.getByLabelText("확인자 이니셜");
    const inputRow = initialsInput.parentElement;
    const confirmButton = screen.getByRole("button", { name: "이니셜로 확정" });
    const rejectButton = screen.getByRole("button", { name: "거절" });

    expect(inputRow).toContainElement(confirmButton);
    expect(inputRow).toContainElement(rejectButton);
    expect(confirmButton.querySelector("svg")).toHaveAttribute("aria-hidden", "true");
    expect(rejectButton.querySelector("svg")).toHaveAttribute("aria-hidden", "true");
    expect(screen.queryByText("확정 기록에 입력한 그대로 남습니다.")).not.toBeInTheDocument();
    expect(screen.queryByText(/체크는 확정/)).not.toBeInTheDocument();
  });

  it("routes an unauthenticated direct entry to login recovery", async () => {
    getPendingMealUsagesMock.mockRejectedValue(new ApiError(401, "AUTHENTICATION_REQUIRED"));

    render(<MealUsageList />);

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/store/login?next=/store/meal-usages"));
  });

  it("renders access denied separately from login recovery", async () => {
    getPendingMealUsagesMock.mockRejectedValue(new ApiError(403, "ACCESS_DENIED"));

    render(<MealUsageList />);

    expect(await screen.findByText("접근 권한이 없습니다")).toBeVisible();
    expect(replace).not.toHaveBeenCalled();
  });

  it("retries a server error", async () => {
    const user = userEvent.setup();
    getPendingMealUsagesMock
      .mockRejectedValueOnce(new ApiError(500, "INTERNAL_SERVER_ERROR"))
      .mockResolvedValueOnce({ items: [pendingItem], page: 0, size: 50, hasNext: false });

    render(<MealUsageList />);

    await user.click(await screen.findByRole("button", { name: "다시 시도" }));

    expect(await screen.findByText("매장 태블릿 입력")).toBeVisible();
    expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(2);
  });

  it("disables manual refresh while pending and keeps rows when it fails", async () => {
    const user = userEvent.setup();
    let rejectRefresh: (error: ApiError) => void = () => undefined;
    getPendingMealUsagesMock
      .mockResolvedValueOnce({ items: [pendingItem], page: 0, size: 50, hasNext: false })
      .mockImplementationOnce(() => new Promise((_, reject) => {
        rejectRefresh = reject as (error: ApiError) => void;
      }));

    render(<MealUsageList />);

    expect(await screen.findByText("매장 태블릿 입력")).toBeVisible();
    const refresh = screen.getByRole("button", { name: "새로고침" });
    await user.click(refresh);
    expect(screen.getByRole("button", { name: "새로고침 중…" })).toBeDisabled();

    rejectRefresh(new ApiError(500, "INTERNAL_SERVER_ERROR"));

    expect(await screen.findByRole("alert")).toHaveTextContent("목록을 불러오지 못했습니다");
    expect(screen.getByText("매장 태블릿 입력")).toBeVisible();
    expect(screen.getByRole("button", { name: "새로고침" })).toBeEnabled();
  });

  it("clears an older refresh alert before exact confirmation commits its success callout", async () => {
    const user = userEvent.setup();
    confirmMealUsageMock.mockResolvedValue();
    getPendingMealUsagesMock
      .mockResolvedValueOnce({ items: [pendingItem], page: 0, size: 50, hasNext: false })
      .mockRejectedValueOnce(new ApiError(500, "INTERNAL_SERVER_ERROR"))
      .mockResolvedValueOnce({ items: [], page: 0, size: 50, hasNext: false });

    render(<MealUsageList />);
    await screen.findByRole("button", { name: /매장 태블릿 입력/ });
    await user.click(screen.getByRole("button", { name: "새로고침" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("목록을 불러오지 못했습니다");

    await user.click(screen.getByRole("button", { name: /매장 태블릿 입력/ }));
    await user.type(screen.getByLabelText("확인자 이니셜"), "HK");
    await user.click(screen.getByRole("button", { name: "이니셜로 확정" }));

    expect(await screen.findByRole("status")).toHaveTextContent("요청을 확정했습니다.");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("serializes a manual refresh before confirmation so an older page cannot race a mutation", async () => {
    const user = userEvent.setup();
    const refresh = deferred<{ items: Array<typeof pendingItem>; page: number; size: number; hasNext: boolean }>();
    getPendingMealUsagesMock
      .mockResolvedValueOnce({ items: [pendingItem], page: 0, size: 50, hasNext: false })
      .mockReturnValueOnce(refresh.promise);

    render(<MealUsageList />);
    await screen.findByRole("button", { name: /매장 태블릿 입력/ });
    await user.click(screen.getByRole("button", { name: "새로고침" }));

    expect(screen.getByRole("button", { name: "새로고침 중…" })).toBeDisabled();
    expect(screen.getByRole("button", { name: /매장 태블릿 입력/ })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: /매장 태블릿 입력/ }));
    expect(screen.queryByRole("heading", { name: "이 거래를 확정할까요?" })).not.toBeInTheDocument();
    expect(confirmMealUsageMock).not.toHaveBeenCalled();

    refresh.resolve({ items: [pendingItem], page: 0, size: 50, hasNext: false });
    await waitFor(() => expect(screen.getByRole("button", { name: "새로고침" })).toBeEnabled());
  });

  it("uses the authoritative pending-list GET with a two-second completed-GET cadence", async () => {
    vi.useFakeTimers();
    getPendingMealUsagesMock.mockResolvedValue({ items: [pendingItem], page: 0, size: 50, hasNext: false });

    render(<MealUsageList />);
    expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(1);
    await flushUpdates();

    await vi.advanceTimersByTimeAsync(1_999);
    expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(2);
    await flushUpdates();
    await vi.advanceTimersByTimeAsync(1_999);
    expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(3);
  });

  it("does not request while hidden or unmounted and immediately refreshes once when visible again", async () => {
    vi.useFakeTimers();
    Object.defineProperty(document, "hidden", { configurable: true, value: true, writable: true });
    getPendingMealUsagesMock.mockResolvedValue({ items: [pendingItem], page: 0, size: 50, hasNext: false });

    const { unmount } = render(<MealUsageList />);
    await vi.advanceTimersByTimeAsync(30_000);
    expect(getPendingMealUsagesMock).not.toHaveBeenCalled();

    Object.defineProperty(document, "hidden", { configurable: true, value: false, writable: true });
    document.dispatchEvent(new Event("visibilitychange"));
    expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(1);
    await flushUpdates();

    Object.defineProperty(document, "hidden", { configurable: true, value: true, writable: true });
    document.dispatchEvent(new Event("visibilitychange"));
    await vi.advanceTimersByTimeAsync(30_000);
    expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(1);

    Object.defineProperty(document, "hidden", { configurable: true, value: false, writable: true });
    document.dispatchEvent(new Event("visibilitychange"));
    expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(2);
    unmount();
    await vi.advanceTimersByTimeAsync(30_000);
    expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(2);
  });

  it("drops a delayed list response after the document becomes hidden", async () => {
    const initialLoad = deferred<{ items: Array<typeof pendingItem>; page: number; size: number; hasNext: boolean }>();
    getPendingMealUsagesMock
      .mockReturnValueOnce(initialLoad.promise)
      .mockResolvedValueOnce({ items: [pendingItem], page: 0, size: 50, hasNext: false });

    render(<MealUsageList />);
    await waitFor(() => expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(1));
    Object.defineProperty(document, "hidden", { configurable: true, value: true, writable: true });
    document.dispatchEvent(new Event("visibilitychange"));
    initialLoad.resolve({ items: [pendingItem], page: 0, size: 50, hasNext: false });
    await initialLoad.promise;
    expect(screen.queryByText("₩12,000")).not.toBeInTheDocument();

    Object.defineProperty(document, "hidden", { configurable: true, value: false, writable: true });
    document.dispatchEvent(new Event("visibilitychange"));
    await waitFor(() => expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(2));
  });

  it("coalesces manual refresh behind a slow automatic GET into one trailing request", async () => {
    vi.useFakeTimers();
    const slowAutomaticGet = deferred<{ items: Array<typeof pendingItem>; page: number; size: number; hasNext: boolean }>();
    getPendingMealUsagesMock
      .mockResolvedValueOnce({ items: [pendingItem], page: 0, size: 50, hasNext: false })
      .mockReturnValueOnce(slowAutomaticGet.promise)
      .mockResolvedValueOnce({ items: [pendingItem], page: 0, size: 50, hasNext: false });

    render(<MealUsageList />);
    await flushUpdates();
    expect(screen.getByRole("button", { name: /매장 태블릿 입력/ })).toBeVisible();
    await vi.advanceTimersByTimeAsync(2_000);
    expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(2);

    fireEvent.click(screen.getByRole("button", { name: "새로고침" }));
    expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(2);
    slowAutomaticGet.resolve({ items: [pendingItem], page: 0, size: 50, hasNext: false });
    await flushUpdates();
    expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(3);
    await vi.advanceTimersByTimeAsync(1_999);
    expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(3);
  });

  it("keeps initials editable and defers a new request until the employee refreshes", async () => {
    vi.useFakeTimers();
    const slowAutomaticGet = deferred<{ items: Array<typeof pendingItem | typeof partnerMobilePendingItem>; page: number; size: number; hasNext: boolean }>();
    getPendingMealUsagesMock
      .mockResolvedValueOnce({ items: [pendingItem], page: 0, size: 50, hasNext: false })
      .mockReturnValueOnce(slowAutomaticGet.promise)
      .mockResolvedValueOnce({ items: [pendingItem, partnerMobilePendingItem], page: 0, size: 50, hasNext: false });

    render(<MealUsageList />);
    await flushUpdates();
    const row = screen.getByRole("button", { name: /매장 태블릿 입력/ });
    fireEvent.click(row);
    const initialsInput = screen.getByLabelText("확인자 이니셜");
    initialsInput.focus();
    fireEvent.change(initialsInput, { target: { value: "HK" } });
    const confirm = screen.getByRole("button", { name: "이니셜로 확정" });
    expect(confirm).toBeEnabled();

    await vi.advanceTimersByTimeAsync(2_000);
    await flushUpdates();
    expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(2);
    expect(screen.getByRole("button", { name: /매장 태블릿 입력/ })).toBeEnabled();
    expect(initialsInput).toBeEnabled();
    expect(initialsInput).toHaveFocus();
    expect(confirm).toBeDisabled();

    slowAutomaticGet.resolve({ items: [pendingItem, partnerMobilePendingItem], page: 0, size: 50, hasNext: false });
    await flushUpdates();
    expect(screen.getByLabelText("확인자 이니셜")).toHaveValue("HK");
    expect(screen.getByRole("button", { name: "새로운 요청이 있습니다." })).toBeVisible();
    expect(screen.getByRole("button", { name: /^새로고침$/ })).toBeVisible();
    expect(screen.queryByText("협력사 B")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "새로운 요청이 있습니다." }));
    await flushUpdates();
    expect(screen.getByText("협력사 B")).toBeVisible();
    expect(screen.queryByRole("button", { name: "새로운 요청이 있습니다." })).not.toBeInTheDocument();
  });

  it("defers a changed pending row until the employee refreshes", async () => {
    vi.useFakeTimers();
    getPendingMealUsagesMock
      .mockResolvedValueOnce({ items: [pendingItem], page: 0, size: 50, hasNext: false })
      .mockResolvedValueOnce({ items: [changedPendingItem], page: 0, size: 50, hasNext: false })
      .mockResolvedValueOnce({ items: [changedPendingItem], page: 0, size: 50, hasNext: false });

    render(<MealUsageList />);
    await flushUpdates();
    fireEvent.click(screen.getByRole("button", { name: /매장 태블릿 입력/ }));
    fireEvent.change(screen.getByLabelText("확인자 이니셜"), { target: { value: "HK" } });

    await vi.advanceTimersByTimeAsync(2_000);
    await flushUpdates();
    expect(screen.getByRole("button", { name: "목록이 변경되었습니다 · 새로고침" })).toBeVisible();
    expect(screen.getAllByText("₩12,000")).not.toHaveLength(0);

    fireEvent.click(screen.getByRole("button", { name: "목록이 변경되었습니다 · 새로고침" }));
    await flushUpdates();
    expect(screen.getAllByText("₩12,001")).not.toHaveLength(0);
  });

  it("uses one immediate reconciliation GET after exact 201 and waits for it before polling again", async () => {
    vi.useFakeTimers();
    const reconciliation = deferred<{ items: Array<typeof pendingItem>; page: number; size: number; hasNext: boolean }>();
    confirmMealUsageMock.mockResolvedValue();
    getPendingMealUsagesMock
      .mockResolvedValueOnce({ items: [pendingItem], page: 0, size: 50, hasNext: false })
      .mockReturnValueOnce(reconciliation.promise)
      .mockResolvedValueOnce({ items: [], page: 0, size: 50, hasNext: false });

    render(<MealUsageList />);
    await flushUpdates();
    fireEvent.click(screen.getByRole("button", { name: /매장 태블릿 입력/ }));
    fireEvent.change(screen.getByLabelText("확인자 이니셜"), { target: { value: "HK" } });
    fireEvent.click(screen.getByRole("button", { name: "이니셜로 확정" }));
    await flushUpdates();
    expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(30_000);
    expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(2);

    reconciliation.resolve({ items: [], page: 0, size: 50, hasNext: false });
    await flushUpdates();
    expect(screen.getByRole("status")).toBeVisible();
    await vi.advanceTimersByTimeAsync(1_999);
    expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(3);
  });

  it.each([
    [new ApiError(401, "AUTHENTICATION_REQUIRED"), "login"],
    [new ApiError(403, "ACCESS_DENIED"), "forbidden"],
  ])("clears sensitive state and stops scheduled polling after automatic %s", async (error, expected) => {
    vi.useFakeTimers();
    getPendingMealUsagesMock
      .mockResolvedValueOnce({ items: [pendingItem], page: 0, size: 50, hasNext: false })
      .mockRejectedValueOnce(error);

    render(<MealUsageList />);
    await flushUpdates();
    fireEvent.click(screen.getByRole("button", { name: /매장 태블릿 입력/ }));
    fireEvent.change(screen.getByLabelText("확인자 이니셜"), { target: { value: "HK" } });
    await vi.advanceTimersByTimeAsync(2_000);
    await flushUpdates();

    if (expected === "login") {
      expect(replace).toHaveBeenCalledWith("/store/login?next=/store/meal-usages");
    } else {
      expect(screen.getByText("접근 권한이 없습니다")).toBeVisible();
    }
    expect(screen.queryByText("₩12,000")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("확인자 이니셜")).not.toBeInTheDocument();
    await vi.advanceTimersByTimeAsync(30_000);
    expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(2);
  });

  it("keeps the last safe list and backs off automatic failures through the 30-second cap before success resets to 2 seconds", async () => {
    vi.useFakeTimers();
    getPendingMealUsagesMock
      .mockResolvedValueOnce({ items: [pendingItem], page: 0, size: 50, hasNext: false })
      .mockRejectedValueOnce(new ApiError(500, "INTERNAL_SERVER_ERROR"))
      .mockRejectedValueOnce(new TypeError("network failed"))
      .mockRejectedValueOnce(new ApiError(500, "INTERNAL_SERVER_ERROR"))
      .mockRejectedValueOnce(new TypeError("network failed"))
      .mockRejectedValueOnce(new ApiError(500, "INTERNAL_SERVER_ERROR"))
      .mockResolvedValueOnce({ items: [], page: 0, size: 50, hasNext: false })
      .mockResolvedValueOnce({ items: [], page: 0, size: 50, hasNext: false });

    render(<MealUsageList />);
    await flushUpdates();
    expect(screen.getByText("₩12,000")).toBeVisible();
    await vi.advanceTimersByTimeAsync(2_000);
    await flushUpdates();
    expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(2);
    expect(screen.getByText("₩12,000")).toBeVisible();

    await vi.advanceTimersByTimeAsync(1_999);
    expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1);
    await flushUpdates();
    expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(3);

    await vi.advanceTimersByTimeAsync(4_000 + 8_000 + 16_000);
    await flushUpdates();
    expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(6);

    await vi.advanceTimersByTimeAsync(29_999);
    expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(6);
    await vi.advanceTimersByTimeAsync(1);
    await flushUpdates();
    expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(7);

    await vi.advanceTimersByTimeAsync(1_999);
    expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(7);
    await vi.advanceTimersByTimeAsync(1);
    expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(8);
  });

  it("keeps an authoritative empty state through a recoverable automatic polling failure", async () => {
    vi.useFakeTimers();
    getPendingMealUsagesMock
      .mockResolvedValueOnce({ items: [], page: 0, size: 50, hasNext: false })
      .mockRejectedValueOnce(new ApiError(500, "INTERNAL_SERVER_ERROR"))
      .mockResolvedValueOnce({ items: [], page: 0, size: 50, hasNext: false });

    render(<MealUsageList />);
    await flushUpdates();
    expect(screen.getByText("확인 대기가 없습니다")).toBeVisible();

    await vi.advanceTimersByTimeAsync(2_000);
    await flushUpdates();
    expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(2);
    expect(screen.getByText("확인 대기가 없습니다")).toBeVisible();
    expect(screen.queryByText("목록을 불러오지 못했습니다")).not.toBeInTheDocument();

    await vi.advanceTimersByTimeAsync(1_999);
    expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(3);
  });

  it("ignores a delayed list result after its lifecycle ends", async () => {
    const initialLoad = deferred<{ items: Array<typeof pendingItem>; page: number; size: number; hasNext: boolean }>();
    getPendingMealUsagesMock.mockReturnValueOnce(initialLoad.promise);

    const { unmount } = render(<MealUsageList />);
    unmount();
    initialLoad.resolve({ items: [pendingItem], page: 0, size: 50, hasNext: false });

    await initialLoad.promise;
    expect(replace).not.toHaveBeenCalled();
  });

  it("removes visible rows before redirecting when a refresh becomes unauthenticated", async () => {
    const user = userEvent.setup();
    getPendingMealUsagesMock
      .mockResolvedValueOnce({ items: [pendingItem], page: 0, size: 50, hasNext: false })
      .mockRejectedValueOnce(new ApiError(401, "AUTHENTICATION_REQUIRED"));

    render(<MealUsageList />);

    expect(await screen.findByText("₩12,000")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "새로고침" }));

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/store/login?next=/store/meal-usages"));
    expect(screen.queryByText("₩12,000")).not.toBeInTheDocument();
    expect(screen.queryByRole("list", { name: "확인 대기 목록" })).not.toBeInTheDocument();
  });

  it("opens a selected transaction summary and sends original initials", async () => {
    const user = userEvent.setup();
    getPendingMealUsagesMock
      .mockResolvedValueOnce({ items: [pendingItem], page: 0, size: 50, hasNext: false })
      .mockResolvedValueOnce({ items: [], page: 0, size: 50, hasNext: false });
    confirmMealUsageMock.mockResolvedValue();

    render(<MealUsageList />);

    const row = await screen.findByRole("button", { name: /매장 태블릿 입력/ });
    await user.click(row);
    expect(screen.getByRole("heading", { name: "이 거래를 확정할까요?" })).toBeVisible();
    expect(screen.getByText("₩12,000", { selector: "dd" })).toBeVisible();
    expect(screen.getByText("매장 태블릿 입력", { selector: "dd" })).toBeVisible();

    await user.type(screen.getByLabelText("확인자 이니셜"), " Hk ");
    await user.click(screen.getByRole("button", { name: "이니셜로 확정" }));

    expect(confirmMealUsageMock).toHaveBeenCalledWith(pendingItem.mealUsageId, " Hk ");
    await waitFor(() => expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(2));
    expect(screen.queryByRole("heading", { name: "이 거래를 확정할까요?" })).not.toBeInTheDocument();
    expect(screen.getByText("확인 대기가 없습니다")).toBeVisible();
    expect(screen.getByRole("status")).toHaveTextContent("요청을 확정했습니다.");
  });

  it("removes a confirmed request notice after five seconds", async () => {
    vi.useFakeTimers();
    confirmMealUsageMock.mockResolvedValue();
    getPendingMealUsagesMock
      .mockResolvedValueOnce({ items: [pendingItem], page: 0, size: 50, hasNext: false })
      .mockResolvedValueOnce({ items: [], page: 0, size: 50, hasNext: false })
      .mockResolvedValue({ items: [], page: 0, size: 50, hasNext: false });

    render(<MealUsageList />);
    await flushUpdates();
    fireEvent.click(screen.getByRole("button", { name: /매장 태블릿 입력/ }));
    fireEvent.change(screen.getByLabelText("확인자 이니셜"), { target: { value: "HK" } });
    fireEvent.click(screen.getByRole("button", { name: "이니셜로 확정" }));
    await flushUpdates();

    expect(screen.getByRole("status")).toHaveTextContent("요청을 확정했습니다.");
    await vi.advanceTimersByTimeAsync(4_999);
    expect(screen.getByRole("status")).toHaveTextContent("요청을 확정했습니다.");
    await vi.advanceTimersByTimeAsync(1);
    await flushUpdates();
    expect(screen.queryByText("요청을 확정했습니다.")).not.toBeInTheDocument();
  });

  it("blocks a duplicate submit synchronously and reconciles after a successful confirmation", async () => {
    const user = userEvent.setup();
    let resolveConfirmation: () => void = () => undefined;
    confirmMealUsageMock.mockImplementationOnce(() => new Promise<void>((resolve) => {
      resolveConfirmation = resolve;
    }));
    getPendingMealUsagesMock
      .mockResolvedValueOnce({ items: [pendingItem], page: 0, size: 50, hasNext: false })
      .mockResolvedValueOnce({ items: [], page: 0, size: 50, hasNext: false });

    render(<MealUsageList />);
    await user.click(await screen.findByRole("button", { name: /매장 태블릿 입력/ }));
    await user.type(screen.getByLabelText("확인자 이니셜"), "HK");
    const submit = screen.getByRole("button", { name: "이니셜로 확정" });
    await user.click(submit);
    await user.click(submit);
    expect(confirmMealUsageMock).toHaveBeenCalledTimes(1);

    resolveConfirmation();
    await waitFor(() => expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(2));
  });

  it("keeps terminal 201 reconciliation GET-only even if the selected row is still returned", async () => {
    const user = userEvent.setup();
    confirmMealUsageMock.mockResolvedValue();
    getPendingMealUsagesMock
      .mockResolvedValueOnce({ items: [pendingItem], page: 0, size: 50, hasNext: false })
      .mockRejectedValueOnce(new ApiError(500, "INTERNAL_SERVER_ERROR"))
      .mockResolvedValueOnce({ items: [pendingItem], page: 0, size: 50, hasNext: false });

    render(<MealUsageList />);
    await user.click(await screen.findByRole("button", { name: /매장 태블릿 입력/ }));
    await user.type(screen.getByLabelText("확인자 이니셜"), "HK");
    await user.click(screen.getByRole("button", { name: "이니셜로 확정" }));

    expect(await screen.findByRole("button", { name: "목록 다시 불러오기" })).toBeVisible();
    expect(screen.queryByRole("button", { name: "이니셜로 확정" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "목록 다시 불러오기" }));
    expect(confirmMealUsageMock).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(3));
    expect(screen.queryByRole("button", { name: "이니셜로 확정" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "목록 다시 불러오기" })).toBeEnabled();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("preserves exact 201 provenance through a failed GET and promotes it after a top refresh omits the row", async () => {
    const user = userEvent.setup();
    confirmMealUsageMock.mockResolvedValue();
    getPendingMealUsagesMock
      .mockResolvedValueOnce({ items: [pendingItem], page: 0, size: 50, hasNext: false })
      .mockRejectedValueOnce(new ApiError(500, "INTERNAL_SERVER_ERROR"))
      .mockResolvedValueOnce({ items: [], page: 0, size: 50, hasNext: false });

    render(<MealUsageList />);
    await user.click(await screen.findByRole("button", { name: /매장 태블릿 입력/ }));
    await user.type(screen.getByLabelText("확인자 이니셜"), "HK");
    await user.click(screen.getByRole("button", { name: "이니셜로 확정" }));
    await screen.findByRole("button", { name: "목록 다시 불러오기" });
    await user.click(screen.getByRole("button", { name: "새로고침" }));

    await waitFor(() => expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(3));
    expect(confirmMealUsageMock).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("status")).toHaveTextContent("요청을 확정했습니다.");
  });

  it("clears a success callout when the employee selects the next row", async () => {
    const user = userEvent.setup();
    confirmMealUsageMock.mockResolvedValue();
    getPendingMealUsagesMock
      .mockResolvedValueOnce({ items: [pendingItem, partnerMobilePendingItem], page: 0, size: 50, hasNext: false })
      .mockResolvedValueOnce({ items: [partnerMobilePendingItem], page: 0, size: 50, hasNext: false });

    render(<MealUsageList />);
    await user.click(await screen.findByRole("button", { name: /매장 태블릿 입력/ }));
    await user.type(screen.getByLabelText("확인자 이니셜"), "HK");
    await user.click(screen.getByRole("button", { name: "이니셜로 확정" }));
    expect(await screen.findByRole("status")).toHaveTextContent("요청을 확정했습니다.");

    await user.click(screen.getByRole("button", { name: /모바일 QR 입력/ }));
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("clears a success callout at manual refresh start and keeps it cleared after 401 recovery", async () => {
    const user = userEvent.setup();
    const refresh = deferred<{ items: Array<typeof partnerMobilePendingItem>; page: number; size: number; hasNext: boolean }>();
    confirmMealUsageMock.mockResolvedValue();
    getPendingMealUsagesMock
      .mockResolvedValueOnce({ items: [pendingItem, partnerMobilePendingItem], page: 0, size: 50, hasNext: false })
      .mockResolvedValueOnce({ items: [partnerMobilePendingItem], page: 0, size: 50, hasNext: false })
      .mockReturnValueOnce(refresh.promise);

    render(<MealUsageList />);
    await user.click(await screen.findByRole("button", { name: /매장 태블릿 입력/ }));
    await user.type(screen.getByLabelText("확인자 이니셜"), "HK");
    await user.click(screen.getByRole("button", { name: "이니셜로 확정" }));
    expect(await screen.findByRole("status")).toHaveTextContent("요청을 확정했습니다.");

    await user.click(screen.getByRole("button", { name: "새로고침" }));
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    refresh.reject(new ApiError(401, "AUTHENTICATION_REQUIRED"));

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/store/login?next=/store/meal-usages"));
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("unlocks an explicit retry only after a network outcome is reconciled to the same pending row", async () => {
    const user = userEvent.setup();
    confirmMealUsageMock.mockRejectedValueOnce(new TypeError("network failed"));
    getPendingMealUsagesMock
      .mockResolvedValueOnce({ items: [pendingItem], page: 0, size: 50, hasNext: false })
      .mockResolvedValueOnce({ items: [pendingItem], page: 0, size: 50, hasNext: false });

    render(<MealUsageList />);
    await user.click(await screen.findByRole("button", { name: /매장 태블릿 입력/ }));
    await user.type(screen.getByLabelText("확인자 이니셜"), "HK");
    await user.click(screen.getByRole("button", { name: "이니셜로 확정" }));

    await waitFor(() => expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(2));
    expect(confirmMealUsageMock).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: "이니셜로 확정" })).toBeEnabled();
    expect(screen.queryByText("요청을 확정했습니다.")).not.toBeInTheDocument();
  });

  it("keeps an unexpected successful confirmation response GET-only when the row remains pending", async () => {
    const user = userEvent.setup();
    confirmMealUsageMock.mockRejectedValueOnce(new UnexpectedConfirmationResponseError());
    getPendingMealUsagesMock
      .mockResolvedValueOnce({ items: [pendingItem], page: 0, size: 50, hasNext: false })
      .mockResolvedValueOnce({ items: [pendingItem], page: 0, size: 50, hasNext: false });

    render(<MealUsageList />);
    await user.click(await screen.findByRole("button", { name: /매장 태블릿 입력/ }));
    await user.type(screen.getByLabelText("확인자 이니셜"), "HK");
    await user.click(screen.getByRole("button", { name: "이니셜로 확정" }));

    await waitFor(() => expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(2));
    expect(confirmMealUsageMock).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("button", { name: "이니셜로 확정" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "목록 다시 불러오기" })).toBeEnabled();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it.each([
    ["404", () => new ApiError(404, "MEAL_USAGE_NOT_FOUND")],
    ["already confirmed 409", () => new ApiError(409, "MEAL_USAGE_ALREADY_CONFIRMED")],
    ["concurrent 409", () => new ApiError(409, "MEAL_USAGE_CONFIRMATION_CONFLICT")],
    ["unexpected success protocol", () => new UnexpectedConfirmationResponseError()],
    ["native network failure", () => new TypeError("network failed")],
    ["500", () => new ApiError(500, "INTERNAL_SERVER_ERROR")],
  ])("never presents confirmation success after %s merely because the next GET omits the row", async (_name, outcome) => {
    const user = userEvent.setup();
    confirmMealUsageMock.mockRejectedValueOnce(outcome());
    getPendingMealUsagesMock
      .mockResolvedValueOnce({ items: [pendingItem], page: 0, size: 50, hasNext: false })
      .mockResolvedValueOnce({ items: [], page: 0, size: 50, hasNext: false });

    render(<MealUsageList />);
    await user.click(await screen.findByRole("button", { name: /매장 태블릿 입력/ }));
    await user.type(screen.getByLabelText("확인자 이니셜"), "HK");
    await user.click(screen.getByRole("button", { name: "이니셜로 확정" }));

    await waitFor(() => expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(2));
    expect(screen.queryByText("요청을 확정했습니다.")).not.toBeInTheDocument();
  });

  it.each([
    new ApiError(404, "MEAL_USAGE_NOT_FOUND"),
    new ApiError(409, "MEAL_USAGE_ALREADY_CONFIRMED"),
    new ApiError(409, "MEAL_USAGE_CONFIRMATION_CONFLICT"),
  ])("keeps stale terminal confirmation responses GET-only when the row is still returned", async (confirmationError) => {
    const user = userEvent.setup();
    confirmMealUsageMock.mockRejectedValueOnce(confirmationError);
    getPendingMealUsagesMock
      .mockResolvedValueOnce({ items: [pendingItem], page: 0, size: 50, hasNext: false })
      .mockResolvedValueOnce({ items: [pendingItem], page: 0, size: 50, hasNext: false });

    render(<MealUsageList />);
    await user.click(await screen.findByRole("button", { name: /매장 태블릿 입력/ }));
    await user.type(screen.getByLabelText("확인자 이니셜"), "HK");
    await user.click(screen.getByRole("button", { name: "이니셜로 확정" }));

    await waitFor(() => expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(2));
    expect(confirmMealUsageMock).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("button", { name: "이니셜로 확정" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "목록 다시 불러오기" })).toBeEnabled();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("keeps initials for a CSRF failure but clears sensitive state for authentication or access loss", async () => {
    const user = userEvent.setup();
    confirmMealUsageMock.mockRejectedValueOnce(new ApiError(403, "CSRF_TOKEN_INVALID"));
    getPendingMealUsagesMock.mockResolvedValueOnce({ items: [pendingItem], page: 0, size: 50, hasNext: false });

    const { unmount } = render(<MealUsageList />);
    await user.click(await screen.findByRole("button", { name: /매장 태블릿 입력/ }));
    await user.type(screen.getByLabelText("확인자 이니셜"), " HK ");
    await user.click(screen.getByRole("button", { name: "이니셜로 확정" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("보안 확인이 만료되었습니다");
    expect(screen.getByLabelText("확인자 이니셜")).toHaveValue(" HK ");
    unmount();

    confirmMealUsageMock.mockRejectedValueOnce(new ApiError(401, "AUTHENTICATION_REQUIRED"));
    getPendingMealUsagesMock.mockResolvedValueOnce({ items: [pendingItem], page: 0, size: 50, hasNext: false });
    render(<MealUsageList />);
    await user.click(await screen.findByRole("button", { name: /매장 태블릿 입력/ }));
    await user.type(screen.getByLabelText("확인자 이니셜"), "HK");
    await user.click(screen.getByRole("button", { name: "이니셜로 확정" }));
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/store/login?next=/store/meal-usages"));
    expect(screen.queryByText("₩12,000")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("확인자 이니셜")).not.toBeInTheDocument();
  });

  it("keeps whitespace-only initials local, then handles a server 400 for nonblank initials", async () => {
    const user = userEvent.setup();
    confirmMealUsageMock.mockRejectedValueOnce(new ApiError(400, "VALIDATION_FAILED"));
    getPendingMealUsagesMock.mockResolvedValueOnce({ items: [pendingItem], page: 0, size: 50, hasNext: false });
    const { unmount } = render(<MealUsageList />);
    await user.click(await screen.findByRole("button", { name: /매장 태블릿 입력/ }));
    const initialsInput = screen.getByLabelText("확인자 이니셜");
    await user.type(initialsInput, "  ");
    await user.click(screen.getByRole("button", { name: "이니셜로 확정" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("이니셜을 입력해 주세요");
    await waitFor(() => expect(initialsInput).toHaveFocus());
    expect(confirmMealUsageMock).not.toHaveBeenCalled();
    await user.clear(initialsInput);
    await user.type(initialsInput, "HK");
    await user.click(screen.getByRole("button", { name: "이니셜로 확정" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("이니셜을 확인해 주세요");
    expect(confirmMealUsageMock).toHaveBeenCalledWith(pendingItem.mealUsageId, "HK");
    unmount();

  });

  it("denies access separately for 403 access", async () => {
    const user = userEvent.setup();

    confirmMealUsageMock.mockRejectedValueOnce(new ApiError(403, "ACCESS_DENIED"));
    getPendingMealUsagesMock.mockResolvedValueOnce({ items: [pendingItem], page: 0, size: 50, hasNext: false });
    render(<MealUsageList />);
    await user.click(await screen.findByRole("button", { name: /매장 태블릿 입력/ }));
    await user.type(screen.getByLabelText("확인자 이니셜"), "HK");
    await user.click(screen.getByRole("button", { name: "이니셜로 확정" }));
    expect(await screen.findByText("접근 권한이 없습니다")).toBeVisible();
    expect(screen.queryByText("₩12,000")).not.toBeInTheDocument();
  });

  it("shows the partner snapshot and lets staff reject through authoritative reconciliation", async () => {
    const user = userEvent.setup();
    rejectMealUsageMock.mockResolvedValue();
    getPendingMealUsagesMock
      .mockResolvedValueOnce({ items: [partnerMobilePendingItem], page: 0, size: 50, hasNext: false })
      .mockResolvedValueOnce({ items: [], page: 0, size: 50, hasNext: false });

    render(<MealUsageList />);
    await user.click(await screen.findByRole("button", { name: /모바일 QR 입력/ }));
    expect(screen.getByRole("listitem")).toHaveTextContent("협력사 B");
    await user.click(screen.getByRole("button", { name: "거절" }));

    await waitFor(() => expect(rejectMealUsageMock).toHaveBeenCalledWith(partnerMobilePendingItem.mealUsageId));
    expect(screen.getByText("확인 대기가 없습니다")).toBeVisible();
    expect(screen.getByRole("status")).toHaveTextContent("요청을 거절했습니다.");
    expect(screen.queryByText("요청을 확정했습니다.")).not.toBeInTheDocument();
  });

  it("removes a rejected request notice after five seconds", async () => {
    vi.useFakeTimers();
    rejectMealUsageMock.mockResolvedValue();
    getPendingMealUsagesMock
      .mockResolvedValueOnce({ items: [partnerMobilePendingItem], page: 0, size: 50, hasNext: false })
      .mockResolvedValueOnce({ items: [], page: 0, size: 50, hasNext: false })
      .mockResolvedValue({ items: [], page: 0, size: 50, hasNext: false });

    render(<MealUsageList />);
    await flushUpdates();
    fireEvent.click(screen.getByRole("button", { name: /모바일 QR 입력/ }));
    fireEvent.click(screen.getByRole("button", { name: "거절" }));
    await flushUpdates();

    expect(screen.getByRole("status")).toHaveTextContent("요청을 거절했습니다.");
    await vi.advanceTimersByTimeAsync(4_999);
    expect(screen.getByRole("status")).toHaveTextContent("요청을 거절했습니다.");
    await vi.advanceTimersByTimeAsync(1);
    await flushUpdates();
    expect(screen.queryByText("요청을 거절했습니다.")).not.toBeInTheDocument();
  });
});
