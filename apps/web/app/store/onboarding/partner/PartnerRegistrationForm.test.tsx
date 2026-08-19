import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, getStoreOnboardingStatus, registerFirstPartner } from "@/lib/store-api";
import { PartnerRegistrationForm } from "./PartnerRegistrationForm";

const replace = vi.fn();
const router = { replace };

vi.mock("next/navigation", () => ({
  useRouter: () => router,
}));

vi.mock("@/lib/store-api", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/lib/store-api")>();
  return { ...original, getStoreOnboardingStatus: vi.fn(), registerFirstPartner: vi.fn() };
});

const getStoreOnboardingStatusMock = vi.mocked(getStoreOnboardingStatus);
const registerFirstPartnerMock = vi.mocked(registerFirstPartner);

beforeEach(() => {
  getStoreOnboardingStatusMock.mockResolvedValue({ onboardingStatus: "PARTNER_REQUIRED", legacy: false });
});

afterEach(() => {
  cleanup();
  vi.resetAllMocks();
});

describe("PartnerRegistrationForm", () => {
  it("submits all explicit partner contract choices and moves to the store ledger", async () => {
    const user = userEvent.setup();
    registerFirstPartnerMock.mockResolvedValue({
      onboardingStatus: "COMPLETE",
      legacy: false,
      created: true,
      partnerDisplayName: "협력사 A",
      paymentType: "PREPAID_WITH_RECEIVABLE_OVERFLOW",
    });
    render(<PartnerRegistrationForm />);

    await screen.findByRole("heading", { name: "첫 협력사 등록하기" });
    fireEvent.change(screen.getByLabelText("협력사명"), { target: { value: "Partner A" } });
    await user.click(screen.getByRole("radio", { name: "선불 후 미수금" }));
    fireEvent.change(screen.getByLabelText("초기 선불 잔액"), { target: { value: "120000" } });
    await user.click(screen.getByRole("radio", { name: "예, 검색에 표시" }));
    fireEvent.submit(screen.getByRole("button", { name: "협력사 등록하기" }).closest("form")!);

    await waitFor(() => expect(registerFirstPartnerMock).toHaveBeenCalledWith({
      partnerName: "Partner A",
      paymentType: "PREPAID_WITH_RECEIVABLE_OVERFLOW",
      initialPrepaidBalanceMinor: 120000,
      qrSelectable: true,
    }));
    expect(JSON.stringify(registerFirstPartnerMock.mock.calls)).not.toContain("storeId");
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/store/meal-usages"));
  });

  it("requires payment type and QR visibility rather than inventing hidden defaults", async () => {
    render(<PartnerRegistrationForm />);

    await screen.findByRole("heading", { name: "첫 협력사 등록하기" });
    fireEvent.change(screen.getByLabelText("협력사명"), { target: { value: "Partner A" } });
    fireEvent.submit(screen.getByRole("button", { name: "협력사 등록하기" }).closest("form")!);

    expect(await screen.findByRole("alert")).toHaveTextContent("결제 유형을 선택해 주세요.");
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
