import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, reauthenticateStoreSession } from "@/lib/store-api";
import {
  archiveStorePartner,
  getArchivePinStatus,
  getStorePartners,
  getStoreProfile,
  setArchivePin,
  updateStorePartnerPaymentType,
  type StorePartner,
} from "@/lib/store-partner-api";
import { StorePartnerProvider, useStorePartnerContext } from "../StorePartnerContext";
import { StoreProfileView } from "./StoreProfileView";

const navigation = vi.hoisted(() => ({
  pathname: "/store/profile",
  replace: vi.fn(),
}));

const partnerMocks = vi.hoisted(() => ({
  archiveStorePartner: vi.fn(),
  getArchivePinStatus: vi.fn(),
  getStorePartners: vi.fn(),
  getStoreProfile: vi.fn(),
  reauthenticateStoreSession: vi.fn(),
  setArchivePin: vi.fn(),
  updateStorePartnerPaymentType: vi.fn(),
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
    archiveStorePartner: partnerMocks.archiveStorePartner,
    getArchivePinStatus: partnerMocks.getArchivePinStatus,
    getStorePartners: partnerMocks.getStorePartners,
    getStoreProfile: partnerMocks.getStoreProfile,
    setArchivePin: partnerMocks.setArchivePin,
    updateStorePartnerPaymentType: partnerMocks.updateStorePartnerPaymentType,
  };
});

vi.mock("@/lib/store-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/store-api")>();
  return { ...actual, reauthenticateStoreSession: partnerMocks.reauthenticateStoreSession };
});

const partnerA: StorePartner = {
  mealContractId: "11111111-1111-4111-8111-111111111111",
  partnerDisplayName: "가나다 협력사",
  partnerKind: "ORGANIZATION",
  paymentType: "POSTPAID",
  qrSelectable: true,
  representativePhone: "010-1234-5678",
  representativeEmail: "owner@example.com",
};
const partnerB: StorePartner = {
  mealContractId: "22222222-2222-4222-8222-222222222222",
  partnerDisplayName: "마바사 협력사",
  partnerKind: "INDIVIDUAL",
  paymentType: "PREPAID_WITH_RECEIVABLE_OVERFLOW",
  qrSelectable: false,
  representativePhone: null,
  representativeEmail: null,
};

function setUrl(pathname: string, search = "") {
  navigation.pathname = pathname;
  window.history.replaceState({}, "", `${pathname}${search}`);
}

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function Workspace({ children }: Readonly<{ children: React.ReactNode }>) {
  return <StorePartnerProvider>{children}</StorePartnerProvider>;
}

function PartnerPaymentProbe() {
  const { partners } = useStorePartnerContext();
  return <output data-testid="partner-payment-probe">{partners.find((partner) => partner.mealContractId === partnerA.mealContractId)?.paymentType}</output>;
}

beforeEach(() => {
  vi.clearAllMocks();
  setUrl("/store/profile");
  navigation.replace.mockImplementation(() => undefined);
  partnerMocks.getStorePartners.mockResolvedValue([partnerA, partnerB]);
  partnerMocks.getStoreProfile.mockResolvedValue({ loginId: "store-hk", storeDisplayName: null });
  partnerMocks.getArchivePinStatus.mockResolvedValue({ configured: true });
  partnerMocks.archiveStorePartner.mockResolvedValue(undefined);
  partnerMocks.reauthenticateStoreSession.mockResolvedValue(undefined);
  partnerMocks.setArchivePin.mockResolvedValue({ configured: true });
  partnerMocks.updateStorePartnerPaymentType.mockResolvedValue({
    ...partnerA,
    paymentType: "PREPAID_WITH_RECEIVABLE_OVERFLOW",
    representativePhone: partnerA.representativePhone,
    representativeEmail: partnerA.representativeEmail,
  });
});

afterEach(() => {
  cleanup();
});

describe("StoreProfileView", () => {
  it("keeps cards to ledger and detail actions, then opens contact details in one modal", async () => {
    const user = userEvent.setup();
    render(<Workspace><StoreProfileView /></Workspace>);

    expect(await screen.findByText("매장 이름 미등록")).toBeInTheDocument();
    expect(screen.getByText("store-hk")).toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: "장부 보기" })).toHaveLength(2);
    expect(screen.getAllByRole("button", { name: "상세" })).toHaveLength(2);
    expect(screen.queryByRole("button", { name: /삭제/ })).not.toBeInTheDocument();
    expect(screen.queryByText("010-1234-5678")).not.toBeInTheDocument();
    expect(screen.queryByText("owner@example.com")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: partnerA.partnerDisplayName }));
    const dialog = await screen.findByRole("dialog", { name: "협력사 상세" });
    expect(dialog).toHaveTextContent(partnerA.partnerDisplayName);
    expect(dialog).toHaveTextContent("단체");
    expect(dialog).toHaveTextContent("010-1234-5678");
    expect(dialog).toHaveTextContent("owner@example.com");
    expect(dialog).toHaveTextContent("현재 결제 유형");
    expect(dialog).toHaveTextContent("후불");
    const ledgerAction = within(dialog).getByRole("link", { name: "장부 보기" });
    const modalActions = [
      ledgerAction,
      within(dialog).getByRole("button", { name: "결제 유형 변경" }),
      within(dialog).getByRole("button", { name: "협력사 삭제" }),
      within(dialog).getByRole("button", { name: /삭제 PIN (설정|변경)/ }),
      within(dialog).getByRole("button", { name: "닫기" }),
    ];
    for (const action of modalActions) {
      expect(action).toHaveClass("rounded-lg", "text-sm", "font-semibold", "min-h-11");
    }
    for (const action of [
      ledgerAction,
      within(dialog).getByRole("button", { name: "결제 유형 변경" }),
    ]) {
      expect(action).toHaveClass("bg-[#f4f6ff]", "text-[#244cda]");
    }
    for (const action of [
      within(dialog).getByRole("button", { name: /삭제 PIN (설정|변경)/ }),
      within(dialog).getByRole("button", { name: "닫기" }),
    ]) {
      expect(action).toHaveClass("border-[var(--border-strong)]", "bg-white", "text-[var(--text-primary)]");
    }
    expect(within(dialog).getByRole("button", { name: "협력사 삭제" })).toHaveClass("text-[var(--danger)]");

    await user.click(screen.getByRole("button", { name: "닫기" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    await user.click(screen.getAllByRole("button", { name: "상세" })[1]);
    const secondDialog = await screen.findByRole("dialog", { name: "협력사 상세" });
    expect(within(secondDialog).getAllByText("미등록")).toHaveLength(2);
    expect(secondDialog).toHaveTextContent("개인");
    expect(secondDialog).toHaveTextContent("선불");
  });

  it("updates payment type, upserts the partner, and keeps the editor open on conflict", async () => {
    const user = userEvent.setup();
    render(<Workspace><PartnerPaymentProbe /><StoreProfileView /></Workspace>);

    await screen.findByText(partnerA.partnerDisplayName);
    await user.click(screen.getAllByRole("button", { name: "상세" })[0]);
    await user.click(screen.getByRole("button", { name: "결제 유형 변경" }));
    await user.selectOptions(screen.getByLabelText("변경할 결제 유형"), "PREPAID_WITH_RECEIVABLE_OVERFLOW");
    await user.type(screen.getByLabelText("전환 시 선불 잔액"), "1500");
    await user.click(screen.getByRole("button", { name: "변경 저장" }));

    await waitFor(() => expect(partnerMocks.updateStorePartnerPaymentType).toHaveBeenCalledWith(
      partnerA.mealContractId,
      {
        expectedPaymentType: "POSTPAID",
        paymentType: "PREPAID_WITH_RECEIVABLE_OVERFLOW",
        prepaidBalanceMinor: 1500,
      },
    ));
    expect(await screen.findByTestId("partner-payment-probe")).toHaveTextContent("PREPAID_WITH_RECEIVABLE_OVERFLOW");
    expect(screen.getByRole("dialog", { name: "협력사 상세" })).toHaveTextContent("선불");

    cleanup();
    partnerMocks.updateStorePartnerPaymentType.mockRejectedValue(new ApiError(409, "STORE_PARTNER_PAYMENT_TERM_BLOCKED"));
    setUrl("/store/profile");
    render(<Workspace><StoreProfileView /></Workspace>);
    await screen.findByText(partnerA.partnerDisplayName);
    await user.click(screen.getAllByRole("button", { name: "상세" })[0]);
    await user.click(screen.getByRole("button", { name: "결제 유형 변경" }));
    await user.selectOptions(screen.getByLabelText("변경할 결제 유형"), "PREPAID_WITH_RECEIVABLE_OVERFLOW");
    await user.type(screen.getByLabelText("전환 시 선불 잔액"), "1000");
    await user.click(screen.getByRole("button", { name: "변경 저장" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("미정산 금액이나 남은 선불 잔액이 있어 변경할 수 없습니다.");
    expect(screen.getByRole("dialog", { name: "결제 유형 변경" })).toBeInTheDocument();
  });

  it("requires the warning and four-digit PIN before archive, preserving wrong-PIN errors", async () => {
    const user = userEvent.setup();
    partnerMocks.archiveStorePartner
      .mockRejectedValueOnce(new ApiError(403, "STORE_ARCHIVE_PIN_INVALID"))
      .mockResolvedValueOnce(undefined);
    render(<Workspace><StoreProfileView /></Workspace>);

    await screen.findByText(partnerA.partnerDisplayName);
    await user.click(screen.getAllByRole("button", { name: "상세" })[0]);
    await user.click(screen.getByRole("button", { name: "협력사 삭제" }));
    const warningDialog = screen.getByRole("dialog", { name: "협력사를 삭제할까요?" });
    expect(warningDialog).toHaveTextContent("기존 장부는 보존되고 신규 사용만 중단됩니다");
    await user.click(screen.getByRole("button", { name: "삭제 PIN 입력" }));
    await user.type(screen.getByLabelText("매장 공용 삭제 PIN"), "1234");
    await user.click(screen.getByRole("button", { name: "최종 삭제" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("삭제 PIN이 올바르지 않습니다.");
    expect(screen.getByRole("dialog", { name: "삭제 PIN 확인" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "최종 삭제" }));
    await waitFor(() => expect(partnerMocks.archiveStorePartner).toHaveBeenNthCalledWith(2, partnerA.mealContractId, "1234"));
    await waitFor(() => expect(partnerMocks.getStorePartners).toHaveBeenCalledTimes(2));
  });

  it("keeps the PIN snapshot across password reauthentication and retries archive once", async () => {
    const user = userEvent.setup();
    partnerMocks.archiveStorePartner
      .mockRejectedValueOnce(new ApiError(403, "PASSWORD_REAUTHENTICATION_REQUIRED"))
      .mockResolvedValueOnce(undefined);
    render(<Workspace><StoreProfileView /></Workspace>);

    await screen.findByText(partnerA.partnerDisplayName);
    await user.click(screen.getAllByRole("button", { name: "상세" })[0]);
    await user.click(screen.getByRole("button", { name: "협력사 삭제" }));
    await user.click(screen.getByRole("button", { name: "삭제 PIN 입력" }));
    await user.type(screen.getByLabelText("매장 공용 삭제 PIN"), "1234");
    await user.click(screen.getByRole("button", { name: "최종 삭제" }));
    expect(await screen.findByRole("dialog", { name: "비밀번호 재확인" })).toBeInTheDocument();

    await user.type(screen.getByLabelText("계정 비밀번호"), "correct-password");
    await user.keyboard("{Enter}");
    await waitFor(() => expect(reauthenticateStoreSession).toHaveBeenCalledWith("correct-password"));
    await waitFor(() => expect(partnerMocks.archiveStorePartner).toHaveBeenNthCalledWith(2, partnerA.mealContractId, "1234"));
    await waitFor(() => expect(partnerMocks.getStorePartners).toHaveBeenCalledTimes(2));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("keeps the reauthentication dialog generic, clears password, and routes unavailable sessions", async () => {
    const user = userEvent.setup();
    partnerMocks.archiveStorePartner.mockRejectedValue(new ApiError(403, "PASSWORD_REAUTHENTICATION_REQUIRED"));
    partnerMocks.reauthenticateStoreSession.mockRejectedValueOnce(new ApiError(401, "SESSION_REAUTHENTICATION_FAILED"));
    render(<Workspace><StoreProfileView /></Workspace>);

    await screen.findByText(partnerA.partnerDisplayName);
    await user.click(screen.getAllByRole("button", { name: "상세" })[0]);
    await user.click(screen.getByRole("button", { name: "협력사 삭제" }));
    await user.click(screen.getByRole("button", { name: "삭제 PIN 입력" }));
    await user.type(screen.getByLabelText("매장 공용 삭제 PIN"), "1234");
    await user.click(screen.getByRole("button", { name: "최종 삭제" }));
    const password = await screen.findByLabelText("계정 비밀번호");
    await user.type(password, "wrong-password");
    await user.click(screen.getByRole("button", { name: "비밀번호 확인" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("비밀번호 재확인에 실패했습니다.");
    expect(password).toHaveValue("");
    expect(password).toHaveFocus();
    expect(partnerMocks.archiveStorePartner).toHaveBeenCalledTimes(1);

    partnerMocks.reauthenticateStoreSession.mockRejectedValueOnce(new ApiError(401, "AUTHENTICATION_REQUIRED"));
    await user.type(password, "correct-password");
    await user.click(screen.getByRole("button", { name: "비밀번호 확인" }));
    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/store/login?next=/store/profile"));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("keeps pending archive and reauthentication modal secrets safe, then stops at one terminal retry", async () => {
    const user = userEvent.setup();
    const initialArchive = deferred<void>();
    partnerMocks.archiveStorePartner.mockImplementationOnce(() => initialArchive.promise);
    render(<Workspace><StoreProfileView /></Workspace>);

    await screen.findByText(partnerA.partnerDisplayName);
    await user.click(screen.getAllByRole("button", { name: "상세" })[0]);
    await user.click(screen.getByRole("button", { name: "협력사 삭제" }));
    await user.click(screen.getByRole("button", { name: "삭제 PIN 입력" }));
    await user.type(screen.getByLabelText("매장 공용 삭제 PIN"), "1234");
    await user.click(screen.getByRole("button", { name: "최종 삭제" }));
    await waitFor(() => expect(partnerMocks.archiveStorePartner).toHaveBeenCalledTimes(1));
    expect(screen.getByRole("button", { name: "뒤로" })).toBeDisabled();
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.getByRole("dialog", { name: "삭제 PIN 확인" })).toBeInTheDocument();
    expect(screen.getByLabelText("매장 공용 삭제 PIN")).toHaveValue("1234");
    initialArchive.resolve(undefined);
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    const retryArchive = deferred<void>();
    partnerMocks.archiveStorePartner.mockClear()
      .mockImplementationOnce(() => retryArchive.promise)
      .mockRejectedValueOnce(new ApiError(403, "PASSWORD_REAUTHENTICATION_REQUIRED"));
    const reauthentication = deferred<void>();
    partnerMocks.reauthenticateStoreSession.mockClear().mockImplementationOnce(() => reauthentication.promise);

    await user.click(screen.getAllByRole("button", { name: "상세" })[0]);
    await user.click(screen.getByRole("button", { name: "협력사 삭제" }));
    await user.click(screen.getByRole("button", { name: "삭제 PIN 입력" }));
    await user.type(screen.getByLabelText("매장 공용 삭제 PIN"), "1234");
    await user.click(screen.getByRole("button", { name: "최종 삭제" }));
    await waitFor(() => expect(partnerMocks.archiveStorePartner).toHaveBeenCalledTimes(1));
    expect(screen.getByRole("button", { name: "뒤로" })).toBeDisabled();
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.getByRole("dialog", { name: "삭제 PIN 확인" })).toBeInTheDocument();
    retryArchive?.reject(new ApiError(403, "PASSWORD_REAUTHENTICATION_REQUIRED"));
    const reauthDialog = await screen.findByRole("dialog", { name: "비밀번호 재확인" });
    const password = screen.getByLabelText("계정 비밀번호");
    await user.type(password, "correct-password");
    await user.click(screen.getByRole("button", { name: "비밀번호 확인" }));
    expect(screen.getByRole("button", { name: "취소" })).toBeDisabled();
    fireEvent.keyDown(window, { key: "Escape" });
    expect(reauthDialog).toBeInTheDocument();
    expect(screen.getByLabelText("계정 비밀번호")).toHaveValue("correct-password");
    reauthentication.resolve(undefined);
    expect(await screen.findByRole("alert")).toHaveTextContent("이 삭제 요청은 종료되었습니다.");
    expect(screen.getByRole("button", { name: "최종 삭제" })).toBeDisabled();
    expect(partnerMocks.archiveStorePartner).toHaveBeenCalledTimes(2);
    expect(partnerMocks.reauthenticateStoreSession).toHaveBeenCalledTimes(1);
  });

  it.each([
    [403, "STORE_ARCHIVE_PIN_INVALID", "삭제 PIN이 올바르지 않습니다."],
    [429, "STORE_ARCHIVE_PIN_LOCKED", "삭제 PIN 입력이 잠겼습니다. 15분 후 다시 시도해 주세요."],
    [409, "STORE_PARTNER_ARCHIVE_BLOCKED", "미정산 금액이나 남은 선불 잔액이 있어 삭제할 수 없습니다."],
  ])("preserves the existing PIN error after reauthentication retry %s", async (status, errorCode, message) => {
    const user = userEvent.setup();
    partnerMocks.archiveStorePartner
      .mockRejectedValueOnce(new ApiError(403, "PASSWORD_REAUTHENTICATION_REQUIRED"))
      .mockRejectedValueOnce(new ApiError(status, errorCode));
    render(<Workspace><StoreProfileView /></Workspace>);
    await screen.findByText(partnerA.partnerDisplayName);
    await user.click(screen.getAllByRole("button", { name: "상세" })[0]);
    await user.click(screen.getByRole("button", { name: "협력사 삭제" }));
    await user.click(screen.getByRole("button", { name: "삭제 PIN 입력" }));
    await user.type(screen.getByLabelText("매장 공용 삭제 PIN"), "1234");
    await user.click(screen.getByRole("button", { name: "최종 삭제" }));
    await user.type(await screen.findByLabelText("계정 비밀번호"), "correct-password");
    await user.click(screen.getByRole("button", { name: "비밀번호 확인" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(message);
    expect(screen.getByRole("dialog", { name: "삭제 PIN 확인" })).toBeInTheDocument();
    expect(partnerMocks.archiveStorePartner).toHaveBeenCalledTimes(2);
  });

  it("offers first-time PIN setup from the archive warning", async () => {
    const user = userEvent.setup();
    partnerMocks.getArchivePinStatus.mockResolvedValue({ configured: false });
    render(<Workspace><StoreProfileView /></Workspace>);

    await screen.findByText(partnerA.partnerDisplayName);
    await user.click(screen.getAllByRole("button", { name: "상세" })[0]);
    await user.click(screen.getByRole("button", { name: "협력사 삭제" }));
    await user.click(screen.getByRole("button", { name: "삭제 PIN 설정" }));
    fireEvent.change(screen.getByLabelText("계정 비밀번호 재확인"), { target: { value: "  correct-password  " } });
    expect(screen.getByText("가입할 때 사용한 비밀번호를 공백 포함 그대로 입력해 주세요.")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("새 삭제 PIN"), { target: { value: "4321" } });
    fireEvent.change(screen.getByLabelText("새 삭제 PIN 확인"), { target: { value: "4321" } });
    await user.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() => expect(partnerMocks.setArchivePin).toHaveBeenCalledWith({
      currentPin: null,
      accountPassword: "  correct-password  ",
      newPin: "4321",
      newPinConfirmation: "4321",
    }));
    expect(screen.getByRole("dialog", { name: "협력사 상세" })).toBeInTheDocument();
  });
});
