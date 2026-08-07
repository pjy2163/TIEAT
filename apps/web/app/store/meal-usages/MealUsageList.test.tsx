import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, confirmMealUsage, getPendingMealUsages, UnexpectedConfirmationResponseError } from "@/lib/store-api";
import { MealUsageList } from "./MealUsageList";

const replace = vi.fn();
const router = { replace };

vi.mock("next/navigation", () => ({
  useRouter: () => router,
}));

vi.mock("@/lib/store-api", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/lib/store-api")>();
  return { ...original, confirmMealUsage: vi.fn(), getPendingMealUsages: vi.fn() };
});

const confirmMealUsageMock = vi.mocked(confirmMealUsage);
const getPendingMealUsagesMock = vi.mocked(getPendingMealUsages);
const pendingItem = {
  mealUsageId: "00000000-0000-0000-0000-000000000001",
  status: "PENDING" as const,
  entrySource: "STORE_TABLET" as const,
  amountMinor: 12000,
  createdAt: "2026-08-05T01:00:00Z",
};

const partnerMobilePendingItem = {
  ...pendingItem,
  mealUsageId: "00000000-0000-0000-0000-000000000002",
  entrySource: "PARTNER_MOBILE" as const,
  amountMinor: 1234567,
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

afterEach(() => {
  cleanup();
  vi.resetAllMocks();
});

describe("MealUsageList", () => {
  it("renders source-specific icons and whole-row buttons with metadata, amount, then status", async () => {
    getPendingMealUsagesMock.mockResolvedValue({ items: [pendingItem, partnerMobilePendingItem], page: 0, size: 50, hasNext: false });

    render(<MealUsageList />);

    expect(await screen.findByText("매장 태블릿 입력")).toBeVisible();
    expect(screen.getByText("모바일 QR 입력")).toBeVisible();
    expect(screen.getAllByText("확인 대기")).toHaveLength(2);
    expect(screen.getByText("₩12,000")).toBeVisible();
    expect(screen.getByText("₩1,234,567")).toBeVisible();
    expect(screen.getAllByText(/2026\. 8\. 5\./)).toHaveLength(2);

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

    expect(await screen.findByText("확인 대기 거래가 없습니다")).toBeVisible();
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

    expect(await screen.findByRole("status")).toHaveTextContent("₩12,000 거래 확정 완료");
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
    expect(screen.queryByRole("list", { name: "확인 대기 거래 목록" })).not.toBeInTheDocument();
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
    expect(screen.getByText("확인 대기 거래가 없습니다")).toBeVisible();
    expect(screen.getByRole("status")).toHaveTextContent("₩12,000 거래 확정 완료");
    expect(screen.getByRole("status")).toHaveTextContent("매장 태블릿 입력");
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

  it("preserves exact 201 provenance through a failed GET and shows success after GET-only retry omits it", async () => {
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
    await user.click(await screen.findByRole("button", { name: "목록 다시 불러오기" }));

    await waitFor(() => expect(getPendingMealUsagesMock).toHaveBeenCalledTimes(3));
    expect(confirmMealUsageMock).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("status")).toHaveTextContent("₩12,000 거래 확정 완료");
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
    expect(await screen.findByRole("status")).toHaveTextContent("₩12,000 거래 확정 완료");

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
    expect(await screen.findByRole("status")).toHaveTextContent("₩12,000 거래 확정 완료");

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
    expect(screen.queryByText(/거래 확정 완료/)).not.toBeInTheDocument();
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
    expect(screen.queryByText(/거래 확정 완료/)).not.toBeInTheDocument();
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
});
