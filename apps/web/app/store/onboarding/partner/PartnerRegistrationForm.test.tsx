import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, getStoreOnboardingStatus, registerFirstPartner, skipFirstPartnerRegistration } from "@/lib/store-api";
import { PartnerRegistrationForm } from "./PartnerRegistrationForm";

const replace = vi.fn();
const router = { replace };

vi.mock("next/navigation", () => ({
  useRouter: () => router,
}));

vi.mock("@/lib/store-api", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/lib/store-api")>();
  return {
    ...original,
    getStoreOnboardingStatus: vi.fn(),
    registerFirstPartner: vi.fn(),
    skipFirstPartnerRegistration: vi.fn(),
  };
});

const getStoreOnboardingStatusMock = vi.mocked(getStoreOnboardingStatus);
const registerFirstPartnerMock = vi.mocked(registerFirstPartner);
const skipFirstPartnerRegistrationMock = vi.mocked(skipFirstPartnerRegistration);

beforeEach(() => {
  getStoreOnboardingStatusMock.mockResolvedValue({ onboardingStatus: "PARTNER_REQUIRED", legacy: false });
});

afterEach(() => {
  cleanup();
  vi.resetAllMocks();
});

describe("PartnerRegistrationForm", () => {
  it("submits the selected partner contract and moves to the store ledger", async () => {
    const user = userEvent.setup();
    registerFirstPartnerMock.mockResolvedValue({
      onboardingStatus: "COMPLETE",
      legacy: false,
      created: true,
      partnerDisplayName: "협력사 A",
      partnerKind: "ORGANIZATION",
      paymentType: "PREPAID_WITH_RECEIVABLE_OVERFLOW",
      mealContractId: "33333333-3333-4333-8333-333333333333",
    });
    render(<PartnerRegistrationForm />);

    await screen.findByRole("heading", { name: "첫 협력사 등록하기" });
    expect(screen.getByText("등록이 끝나면 해당 협력사의 월별 장부로 이동합니다. 입력한 결제 조건은 등록 시 함께 저장되며, 등록 전까지 자유롭게 선택할 수 있습니다. 이 설명은 나중에 자유롭게 변경 가능합니다.")).toBeInTheDocument();
    await user.type(screen.getByLabelText("협력사명"), "Partner A");
    await user.click(screen.getByRole("radio", { name: "단체" }));
    await user.click(screen.getByRole("radio", { name: "선불" }));
    await user.type(screen.getByLabelText("초기 선불 잔액"), "79000");
    expect(screen.getByLabelText("초기 선불 잔액")).toHaveValue("79,000");
    fireEvent.submit(screen.getByRole("button", { name: "협력사 등록하기" }).closest("form")!);

    await waitFor(() => expect(registerFirstPartnerMock).toHaveBeenCalledWith({
      partnerName: "Partner A",
      partnerKind: "ORGANIZATION",
      paymentType: "PREPAID_WITH_RECEIVABLE_OVERFLOW",
      initialPrepaidBalanceMinor: 79000,
      qrSelectable: true,
    }));
    expect(JSON.stringify(registerFirstPartnerMock.mock.calls)).not.toContain("storeId");
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/store/meal-usages/months?mealContractId=33333333-3333-4333-8333-333333333333"));
  });

  it("requires payment type while keeping QR visibility out of the customer form", async () => {
    render(<PartnerRegistrationForm />);

    await screen.findByRole("heading", { name: "첫 협력사 등록하기" });
    fireEvent.change(screen.getByLabelText("협력사명"), { target: { value: "Partner A" } });
    fireEvent.click(screen.getByRole("radio", { name: "단체" }));
    fireEvent.submit(screen.getByRole("button", { name: "협력사 등록하기" }).closest("form")!);

    expect(await screen.findByRole("alert")).toHaveTextContent("결제 유형을 선택해 주세요.");
    expect(screen.queryByText(/QR/)).not.toBeInTheDocument();
    expect(registerFirstPartnerMock).not.toHaveBeenCalled();
  });

  it("skips the first partner and moves to the ledger", async () => {
    const user = userEvent.setup();
    skipFirstPartnerRegistrationMock.mockResolvedValue({ onboardingStatus: "COMPLETE", legacy: false });
    render(<PartnerRegistrationForm />);

    await user.click(await screen.findByRole("button", { name: "나중에 협력사 추가" }));

    await waitFor(() => expect(skipFirstPartnerRegistrationMock).toHaveBeenCalledOnce());
    expect(replace).toHaveBeenCalledWith("/store/meal-usages");
  });

  it("rejects an empty prepaid balance before sending the registration", async () => {
    const user = userEvent.setup();
    render(<PartnerRegistrationForm />);

    await screen.findByRole("heading", { name: "첫 협력사 등록하기" });
    await user.type(screen.getByLabelText("협력사명"), "Partner A");
    await user.click(screen.getByRole("radio", { name: "단체" }));
    await user.click(screen.getByRole("radio", { name: "선불" }));
    fireEvent.submit(screen.getByRole("button", { name: "협력사 등록하기" }).closest("form")!);

    expect(await screen.findByRole("alert")).toHaveTextContent("초기 선불 잔액을 0 이상의 정수로 입력해 주세요.");
    expect(registerFirstPartnerMock).not.toHaveBeenCalled();
  });

  it("redirects an interrupted account back to registration and a complete account to the ledger", async () => {
    getStoreOnboardingStatusMock.mockResolvedValue({ onboardingStatus: "COMPLETE", legacy: false });
    render(<PartnerRegistrationForm />);

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/store/meal-usages"));
  });

  it("returns an expired session to login with the partner recovery destination", async () => {
    getStoreOnboardingStatusMock.mockRejectedValue(new ApiError(401, "AUTHENTICATION_REQUIRED"));
    render(<PartnerRegistrationForm />);

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/store/login?next=/store/onboarding/partner"));
  });
});
