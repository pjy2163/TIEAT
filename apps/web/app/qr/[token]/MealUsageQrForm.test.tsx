import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  cancelPublicMealUsage,
  createPublicMealUsage,
  getPublicMealUsageRequest,
  getPublicMealUsageQrContext,
  PublicQrApiError,
} from "@/lib/public-qr-api";
import { MealUsageQrForm } from "./MealUsageQrForm";

vi.mock("@/lib/public-qr-api", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/lib/public-qr-api")>();
  return {
    ...original,
    cancelPublicMealUsage: vi.fn(),
    createPublicMealUsage: vi.fn(),
    getPublicMealUsageRequest: vi.fn(),
    getPublicMealUsageQrContext: vi.fn(),
  };
});

const getPublicMealUsageQrContextMock = vi.mocked(getPublicMealUsageQrContext);
const createPublicMealUsageMock = vi.mocked(createPublicMealUsage);
const getPublicMealUsageRequestMock = vi.mocked(getPublicMealUsageRequest);
const cancelPublicMealUsageMock = vi.mocked(cancelPublicMealUsage);
const token = "qR8wszyH5CXUTpt-Np5deNiRFi9OKKcjPCAwXWpEM5s";
const mealContractId = "00000000-0000-0000-0000-000000000001";
const idempotencyKey = "00000000-0000-0000-0000-000000000003";
const nextIdempotencyKey = "00000000-0000-0000-0000-000000000004";

function browserCrypto() {
  let requestKeyByte = 7;
  return {
    randomUUID: vi.fn(() => idempotencyKey),
    getRandomValues: vi.fn((values: Uint8Array) => {
      values.fill(requestKeyByte);
      requestKeyByte += 1;
      return values;
    }),
    subtle: {
      digest: vi.fn(async () => new Uint8Array(32).fill(9).buffer),
    },
  };
}

afterEach(() => {
  cleanup();
  window.sessionStorage.clear();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.resetAllMocks();
});

describe("MealUsageQrForm", () => {
  it("loads QR options then submits a minimal pending request and shows no personal history or balance", async () => {
    const user = userEvent.setup();
    const crypto = browserCrypto();
    crypto.randomUUID
      .mockReturnValueOnce(idempotencyKey)
      .mockReturnValueOnce(nextIdempotencyKey);
    vi.stubGlobal("crypto", crypto);
    getPublicMealUsageQrContextMock.mockResolvedValue({
      storeDisplayName: "강남점",
      partners: [{ mealContractId, partnerDisplayName: "협력사 A" }],
      qrExpiresAt: "2026-08-09T01:00:00Z",
      acceptingNewRequests: true,
    });
    createPublicMealUsageMock
      .mockResolvedValueOnce({
        mealUsageId: "00000000-0000-0000-0000-000000000002",
        status: "PENDING",
        amountMinor: 8_500,
        createdAt: "2026-08-09T01:00:00Z",
      })
      .mockResolvedValueOnce({
        mealUsageId: "00000000-0000-0000-0000-000000000005",
        status: "PENDING",
        amountMinor: 8_500,
        createdAt: "2026-08-09T01:01:00Z",
      });
    cancelPublicMealUsageMock.mockResolvedValue({
      mealUsageId: "00000000-0000-0000-0000-000000000002",
      status: "CANCELLED",
      amountMinor: 8_500,
      createdAt: "2026-08-09T01:00:00Z",
    });

    render(<MealUsageQrForm token={token} />);
    expect(await screen.findByRole("heading", { name: "강남점 식대 요청" })).toBeVisible();
    expect(screen.getByText("협력사, 고객 이름과 금액을 입력해 주세요.")).toBeVisible();
    expect(screen.getByText("공백을 제외한 두 글자 이상을 입력해주세요.")).toBeVisible();
    expect(screen.queryByText("원 단위로 1원 이상 1,000,000원 이하를 입력해 주세요.")).not.toBeInTheDocument();
    await user.type(screen.getByLabelText("협력사 검색"), "협력");
    await user.click(screen.getByRole("button", { name: "협력사 A" }));
    await user.type(screen.getByLabelText("고객 이름"), "홍길동");
    await user.type(screen.getByLabelText("금액"), "8500");
    await user.click(screen.getByRole("button", { name: "요청 보내기" }));

    await waitFor(() => expect(createPublicMealUsageMock).toHaveBeenCalledWith(
      token, idempotencyKey, expect.stringMatching(/^[A-Za-z0-9_-]{43}$/), mealContractId, "홍길동", 8_500,
      expect.stringMatching(/^[A-Za-z0-9_-]{43}$/)
    ));
    expect(await screen.findByRole("status")).toHaveTextContent("확인 대기 요청을 보냈습니다");
    expect(screen.getByRole("status")).toHaveTextContent("₩8,500");
    expect(document.body.textContent).not.toMatch(/선불|개인 식별|거래 이력/);
    const publicRequestKey = createPublicMealUsageMock.mock.calls[0][2];
    await user.click(screen.getByRole("button", { name: "요청 취소" }));
    await waitFor(() => expect(cancelPublicMealUsageMock).toHaveBeenCalledWith(
      token, "00000000-0000-0000-0000-000000000002", idempotencyKey, publicRequestKey
    ));
    expect(await screen.findByRole("status")).toHaveTextContent("요청을 취소했습니다");
    expect(screen.getByLabelText("고객 이름")).toHaveValue("홍길동");
    expect(screen.getByLabelText("금액")).toHaveValue("8500");
    await user.click(screen.getByRole("button", { name: "요청 보내기" }));
    await waitFor(() => expect(createPublicMealUsageMock).toHaveBeenCalledTimes(2));
    expect(createPublicMealUsageMock.mock.calls[1]).toEqual([
      token,
      nextIdempotencyKey,
      expect.not.stringMatching(new RegExp(`^${publicRequestKey}$`)),
      mealContractId,
      "홍길동",
      8_500,
      expect.stringMatching(/^[A-Za-z0-9_-]{43}$/),
    ]);
    expect(createPublicMealUsageMock.mock.calls[1][6]).toBe(createPublicMealUsageMock.mock.calls[0][6]);
    expect(await screen.findByRole("status")).toHaveTextContent("확인 대기 요청을 보냈습니다");
  });

  it("counts down from five seconds then returns to a blank request form after a pending request succeeds", async () => {
    vi.useFakeTimers();
    vi.stubGlobal("crypto", browserCrypto());
    getPublicMealUsageQrContextMock.mockResolvedValue({
      storeDisplayName: "강남점",
      partners: [{ mealContractId, partnerDisplayName: "협력사 A" }],
      qrExpiresAt: "2026-08-09T01:00:00Z",
      acceptingNewRequests: true,
    });
    createPublicMealUsageMock.mockResolvedValue({
      mealUsageId: "00000000-0000-0000-0000-000000000002",
      status: "PENDING",
      amountMinor: 8_500,
      createdAt: "2026-08-09T01:00:00Z",
    });

    render(<MealUsageQrForm token={token} />);
    await act(async () => {
      await Promise.resolve();
    });
    fireEvent.change(screen.getByLabelText("협력사 검색"), { target: { value: "협력" } });
    fireEvent.click(screen.getByRole("button", { name: "협력사 A" }));
    fireEvent.change(screen.getByLabelText("고객 이름"), { target: { value: "홍길동" } });
    fireEvent.change(screen.getByLabelText("금액"), { target: { value: "8500" } });
    fireEvent.click(screen.getByRole("button", { name: "요청 보내기" }));
    await act(async () => {
      await Promise.resolve();
    });

    expect(screen.getByRole("status")).toHaveTextContent("5초 뒤 새 요청을 입력할 수 있는 화면으로 돌아갑니다.");
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_000);
    });
    expect(screen.getByRole("status")).toHaveTextContent("4초 뒤 새 요청을 입력할 수 있는 화면으로 돌아갑니다.");
    await act(async () => {
      await vi.advanceTimersByTimeAsync(4_000);
    });

    expect(screen.getByRole("heading", { name: "강남점 식대 요청" })).toBeVisible();
    expect(screen.getByLabelText("협력사 검색")).toHaveValue("");
    expect(screen.getByLabelText("고객 이름")).toHaveValue("");
    expect(screen.getByLabelText("금액")).toHaveValue("");
    expect(screen.queryByRole("button", { name: "요청 취소" })).not.toBeInTheDocument();
    expect(screen.queryByText(/방금 보낸 요청/)).not.toBeInTheDocument();
    expect(createPublicMealUsageMock).toHaveBeenCalledTimes(1);
  });

  it("keeps the same idempotency key for a network retry and preserves form input", async () => {
    const user = userEvent.setup();
    vi.stubGlobal("crypto", browserCrypto());
    getPublicMealUsageQrContextMock.mockResolvedValue({
      storeDisplayName: "강남점",
      partners: [{ mealContractId, partnerDisplayName: "협력사 A" }],
      qrExpiresAt: "2026-08-09T01:00:00Z",
      acceptingNewRequests: true,
    });
    createPublicMealUsageMock
      .mockRejectedValueOnce(new TypeError("network failed"))
      .mockResolvedValueOnce({
        mealUsageId: "00000000-0000-0000-0000-000000000002",
        status: "PENDING",
        amountMinor: 8_500,
        createdAt: "2026-08-09T01:00:00Z",
      });

    render(<MealUsageQrForm token={token} />);
    await screen.findByRole("heading", { name: "강남점 식대 요청" });
    await user.type(screen.getByLabelText("협력사 검색"), "협력");
    await user.click(screen.getByRole("button", { name: "협력사 A" }));
    await user.type(screen.getByLabelText("고객 이름"), "홍길동");
    await user.type(screen.getByLabelText("금액"), "8500");
    await user.click(screen.getByRole("button", { name: "요청 보내기" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("입력은 유지");
    expect(screen.getByLabelText("고객 이름")).toHaveValue("홍길동");
    expect(screen.getByLabelText("금액")).toHaveValue("8500");
    await user.click(screen.getByRole("button", { name: "요청 보내기" }));

    await waitFor(() => expect(createPublicMealUsageMock).toHaveBeenCalledTimes(2));
    expect(createPublicMealUsageMock.mock.calls.map((call) => call[1])).toEqual([idempotencyKey, idempotencyKey]);
    expect(createPublicMealUsageMock.mock.calls.map((call) => call[2])).toEqual([
      createPublicMealUsageMock.mock.calls[0][2],
      createPublicMealUsageMock.mock.calls[0][2],
    ]);
  });

  it("recovers one same-tab pending request without sending another POST", async () => {
    const user = userEvent.setup();
    vi.stubGlobal("crypto", browserCrypto());
    const pendingUsage = {
      mealUsageId: "00000000-0000-0000-0000-000000000002",
      status: "PENDING" as const,
      amountMinor: 8_500,
      createdAt: "2026-08-09T01:00:00Z",
    };
    getPublicMealUsageQrContextMock.mockResolvedValue({
      storeDisplayName: "강남점",
      partners: [{ mealContractId, partnerDisplayName: "협력사 A" }],
      qrExpiresAt: "2026-08-09T01:00:00Z",
      acceptingNewRequests: true,
    });
    createPublicMealUsageMock.mockResolvedValue(pendingUsage);
    getPublicMealUsageRequestMock.mockResolvedValue(pendingUsage);

    render(<MealUsageQrForm token={token} />);
    await screen.findByRole("heading", { name: "강남점 식대 요청" });
    await user.type(screen.getByLabelText("협력사 검색"), "협력");
    await user.click(screen.getByRole("button", { name: "협력사 A" }));
    await user.type(screen.getByLabelText("고객 이름"), "홍길동");
    await user.type(screen.getByLabelText("금액"), "8500");
    await user.click(screen.getByRole("button", { name: "요청 보내기" }));
    await screen.findByRole("status");
    const publicRequestKey = createPublicMealUsageMock.mock.calls[0][2];
    const publicClientKey = createPublicMealUsageMock.mock.calls[0][6];
    expect(publicClientKey).toEqual(expect.stringMatching(/^[A-Za-z0-9_-]{43}$/));
    const clientKeyStorageKey = Object.keys(window.sessionStorage)
      .find((key) => key.startsWith("tieat.public-qr-client-key.v1:"));
    expect(clientKeyStorageKey).toBeDefined();
    expect(window.sessionStorage.getItem(clientKeyStorageKey!)).toBe(publicClientKey);
    expect(window.sessionStorage.getItem("tieat.public-meal-usage-request.v1")).not.toContain(token);

    cleanup();
    render(<MealUsageQrForm token={token} />);

    await waitFor(() => expect(getPublicMealUsageRequestMock).toHaveBeenCalledWith(
      token, pendingUsage.mealUsageId, idempotencyKey, publicRequestKey
    ));
    expect(await screen.findByRole("status")).toHaveTextContent("확인 대기 요청을 보냈습니다");
    expect(createPublicMealUsageMock).toHaveBeenCalledTimes(1);
    expect(window.sessionStorage.getItem(clientKeyStorageKey!)).toBe(publicClientKey);
  });

  it("keeps invalid QR state free of partner controls and blocks local invalid amounts", async () => {
    getPublicMealUsageQrContextMock.mockRejectedValue(new PublicQrApiError(404, "PUBLIC_MEAL_USAGE_QR_NOT_FOUND"));
    render(<MealUsageQrForm token={token} />);
    expect(await screen.findByRole("heading", { name: "이 QR을 사용할 수 없습니다" })).toBeVisible();
    expect(screen.queryByLabelText("협력사 검색")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "다시 시도" })).not.toBeInTheDocument();

    cleanup();
    const user = userEvent.setup();
    vi.stubGlobal("crypto", browserCrypto());
    getPublicMealUsageQrContextMock.mockResolvedValue({
      storeDisplayName: "강남점",
      partners: [{ mealContractId, partnerDisplayName: "협력사 A" }],
      qrExpiresAt: "2026-08-09T01:00:00Z",
      acceptingNewRequests: true,
    });
    render(<MealUsageQrForm token={token} />);
    await screen.findByRole("heading", { name: "강남점 식대 요청" });
    await user.type(screen.getByLabelText("협력사 검색"), "협력");
    await user.click(screen.getByRole("button", { name: "협력사 A" }));
    await user.type(screen.getByLabelText("고객 이름"), "홍길동");
    await user.type(screen.getByLabelText("금액"), "0");
    await user.click(screen.getByRole("button", { name: "요청 보내기" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("1원 이상");
    expect(createPublicMealUsageMock).not.toHaveBeenCalled();
  });

  it("retries a transient QR-context load failure without exposing retry for an invalid QR", async () => {
    const user = userEvent.setup();
    getPublicMealUsageQrContextMock
      .mockRejectedValueOnce(new TypeError("network failed"))
      .mockResolvedValueOnce({
        storeDisplayName: "강남점",
        partners: [{ mealContractId, partnerDisplayName: "협력사 A" }],
        qrExpiresAt: "2026-08-09T01:00:00Z",
        acceptingNewRequests: true,
      });

    render(<MealUsageQrForm token={token} />);
    expect(await screen.findByRole("heading", { name: "QR 정보를 불러오지 못했습니다" })).toBeVisible();
    await user.click(screen.getByRole("button", { name: "다시 시도" }));

    await waitFor(() => expect(getPublicMealUsageQrContextMock).toHaveBeenCalledTimes(2));
    expect(await screen.findByRole("heading", { name: "강남점 식대 요청" })).toBeVisible();
  });

  it("shows only active partner matches after two non-space characters and requires an explicit selection", async () => {
    const user = userEvent.setup();
    getPublicMealUsageQrContextMock.mockResolvedValue({
      storeDisplayName: "강남점",
      partners: [
        { mealContractId, partnerDisplayName: "협력사 A" },
        { mealContractId: "00000000-0000-0000-0000-000000000004", partnerDisplayName: "가 나 협력사" },
      ],
      qrExpiresAt: "2026-08-09T01:00:00Z",
      acceptingNewRequests: true,
    });

    render(<MealUsageQrForm token={token} />);
    const search = await screen.findByLabelText("협력사 검색");
    await user.type(search, "가");
    expect(screen.queryByRole("button", { name: "가 나 협력사" })).not.toBeInTheDocument();
    await user.type(search, " 나");
    expect(screen.getByRole("button", { name: "가 나 협력사" })).toBeVisible();
    expect(screen.queryByRole("button", { name: "협력사 A" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "가 나 협력사" }));
    expect(search).toHaveValue("가 나 협력사");
    expect(screen.queryByText("선택됨: 가 나 협력사")).not.toBeInTheDocument();
  });

  it("keeps input when the store pauses creation during submit", async () => {
    const user = userEvent.setup();
    vi.stubGlobal("crypto", browserCrypto());
    getPublicMealUsageQrContextMock.mockResolvedValue({
      storeDisplayName: "강남점",
      partners: [{ mealContractId, partnerDisplayName: "협력사 A" }],
      qrExpiresAt: "2026-08-09T01:00:00Z",
      acceptingNewRequests: true,
    });
    createPublicMealUsageMock.mockRejectedValue(new PublicQrApiError(503, "PUBLIC_QR_CREATION_PAUSED"));

    render(<MealUsageQrForm token={token} />);
    await screen.findByRole("heading", { name: "강남점 식대 요청" });
    await user.type(screen.getByLabelText("협력사 검색"), "협력");
    await user.click(screen.getByRole("button", { name: "협력사 A" }));
    await user.type(screen.getByLabelText("고객 이름"), "홍길동");
    await user.type(screen.getByLabelText("금액"), "8500");
    await user.click(screen.getByRole("button", { name: "요청 보내기" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("새 요청이 일시 중지되었습니다");
    expect(screen.getByLabelText("고객 이름")).toHaveValue("홍길동");
    expect(screen.getByLabelText("금액")).toHaveValue("8500");
  });
});
