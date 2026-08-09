import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  createPublicMealUsage,
  getPublicMealUsageQrContext,
  PublicQrApiError,
} from "@/lib/public-qr-api";
import { MealUsageQrForm } from "./MealUsageQrForm";

vi.mock("@/lib/public-qr-api", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/lib/public-qr-api")>();
  return { ...original, createPublicMealUsage: vi.fn(), getPublicMealUsageQrContext: vi.fn() };
});

const getPublicMealUsageQrContextMock = vi.mocked(getPublicMealUsageQrContext);
const createPublicMealUsageMock = vi.mocked(createPublicMealUsage);
const token = "qR8wszyH5CXUTpt-Np5deNiRFi9OKKcjPCAwXWpEM5s";
const mealContractId = "00000000-0000-0000-0000-000000000001";
const idempotencyKey = "00000000-0000-0000-0000-000000000003";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.resetAllMocks();
});

describe("MealUsageQrForm", () => {
  it("loads QR options then submits a minimal pending request and shows no personal history or balance", async () => {
    const user = userEvent.setup();
    vi.stubGlobal("crypto", { randomUUID: vi.fn(() => idempotencyKey) });
    getPublicMealUsageQrContextMock.mockResolvedValue({
      storeDisplayName: "강남점",
      partners: [{ mealContractId, partnerDisplayName: "협력사 A" }],
      qrExpiresAt: "2026-08-09T01:00:00Z",
    });
    createPublicMealUsageMock.mockResolvedValue({
      mealUsageId: "00000000-0000-0000-0000-000000000002",
      status: "PENDING",
      amountMinor: 8_500,
      createdAt: "2026-08-09T01:00:00Z",
    });

    render(<MealUsageQrForm token={token} />);
    expect(await screen.findByRole("heading", { name: "강남점 식대 입력" })).toBeVisible();
    await user.selectOptions(screen.getByLabelText("협력사"), mealContractId);
    await user.type(screen.getByLabelText("금액"), "8500");
    await user.click(screen.getByRole("button", { name: "확인 대기 요청 보내기" }));

    await waitFor(() => expect(createPublicMealUsageMock).toHaveBeenCalledWith(token, idempotencyKey, mealContractId, 8_500));
    expect(await screen.findByRole("status")).toHaveTextContent("확인 대기 요청을 보냈습니다");
    expect(screen.getByRole("status")).toHaveTextContent("₩8,500");
    expect(document.body.textContent).not.toMatch(/선불|개인 식별|거래 이력/);
  });

  it("keeps the same idempotency key for a network retry and preserves form input", async () => {
    const user = userEvent.setup();
    vi.stubGlobal("crypto", { randomUUID: vi.fn(() => idempotencyKey) });
    getPublicMealUsageQrContextMock.mockResolvedValue({
      storeDisplayName: "강남점",
      partners: [{ mealContractId, partnerDisplayName: "협력사 A" }],
      qrExpiresAt: "2026-08-09T01:00:00Z",
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
    await screen.findByRole("heading", { name: "강남점 식대 입력" });
    await user.selectOptions(screen.getByLabelText("협력사"), mealContractId);
    await user.type(screen.getByLabelText("금액"), "8500");
    await user.click(screen.getByRole("button", { name: "확인 대기 요청 보내기" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("입력은 유지");
    expect(screen.getByLabelText("금액")).toHaveValue("8500");
    await user.click(screen.getByRole("button", { name: "확인 대기 요청 보내기" }));

    await waitFor(() => expect(createPublicMealUsageMock).toHaveBeenCalledTimes(2));
    expect(createPublicMealUsageMock.mock.calls.map((call) => call[1])).toEqual([idempotencyKey, idempotencyKey]);
  });

  it("keeps invalid QR state free of partner controls and blocks local invalid amounts", async () => {
    getPublicMealUsageQrContextMock.mockRejectedValue(new PublicQrApiError(404, "PUBLIC_MEAL_USAGE_QR_NOT_FOUND"));
    render(<MealUsageQrForm token={token} />);
    expect(await screen.findByRole("heading", { name: "이 QR을 사용할 수 없습니다" })).toBeVisible();
    expect(screen.queryByLabelText("협력사")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "다시 시도" })).not.toBeInTheDocument();

    cleanup();
    const user = userEvent.setup();
    vi.stubGlobal("crypto", { randomUUID: vi.fn(() => idempotencyKey) });
    getPublicMealUsageQrContextMock.mockResolvedValue({
      storeDisplayName: "강남점",
      partners: [{ mealContractId, partnerDisplayName: "협력사 A" }],
      qrExpiresAt: "2026-08-09T01:00:00Z",
    });
    render(<MealUsageQrForm token={token} />);
    await screen.findByRole("heading", { name: "강남점 식대 입력" });
    await user.selectOptions(screen.getByLabelText("협력사"), mealContractId);
    await user.type(screen.getByLabelText("금액"), "0");
    await user.click(screen.getByRole("button", { name: "확인 대기 요청 보내기" }));
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
      });

    render(<MealUsageQrForm token={token} />);
    expect(await screen.findByRole("heading", { name: "QR 정보를 불러오지 못했습니다" })).toBeVisible();
    await user.click(screen.getByRole("button", { name: "다시 시도" }));

    await waitFor(() => expect(getPublicMealUsageQrContextMock).toHaveBeenCalledTimes(2));
    expect(await screen.findByRole("heading", { name: "강남점 식대 입력" })).toBeVisible();
  });
});
