import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/store-api";
import { getStorePartners, getStoreProfile, createStorePartner, type StorePartner } from "@/lib/store-partner-api";
import { StorePartnerProvider, useStorePartnerContext } from "../../StorePartnerContext";
import { StorePartnerRegistrationForm } from "./StorePartnerRegistrationForm";

const navigation = vi.hoisted(() => ({
  pathname: "/store/partners/new",
  replace: vi.fn(),
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
    createStorePartner: vi.fn(),
    getStorePartners: vi.fn(),
    getStoreProfile: vi.fn(),
  };
});

const createStorePartnerMock = vi.mocked(createStorePartner);
const getStorePartnersMock = vi.mocked(getStorePartners);
const getStoreProfileMock = vi.mocked(getStoreProfile);

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

function Workspace({ children }: Readonly<{ children: React.ReactNode }>) {
  return <StorePartnerProvider>{children}</StorePartnerProvider>;
}

function PartnerProbe() {
  const { partners } = useStorePartnerContext();
  return <output data-testid="partner-probe">{partners.map((partner) => partner.mealContractId).join(",")}</output>;
}

beforeEach(() => {
  vi.clearAllMocks();
  setUrl("/store/partners/new");
  navigation.replace.mockImplementation(() => undefined);
  getStorePartnersMock.mockResolvedValue([partnerA, partnerB]);
  getStoreProfileMock.mockResolvedValue({ loginId: "store-hk", storeDisplayName: null });
  createStorePartnerMock.mockResolvedValue(partnerA);
  vi.stubGlobal("crypto", { randomUUID: vi.fn(() => "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa") });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("StorePartnerRegistrationForm", () => {
  it("validates prepaid input and sends POSTPAID with an explicit zero balance", async () => {
    const user = userEvent.setup();
    render(<Workspace><StorePartnerRegistrationForm /></Workspace>);
    await screen.findByRole("button", { name: "협력사 등록하기" });

    await user.type(screen.getByLabelText("협력사명"), "  새 협력사  ");
    await user.click(screen.getByRole("radio", { name: "단체" }));
    await user.click(screen.getByRole("radio", { name: "선불" }));
    await user.click(screen.getByRole("button", { name: "협력사 등록하기" }));
    expect(screen.getByRole("alert")).toHaveTextContent("초기 선불 잔액");
    expect(createStorePartnerMock).not.toHaveBeenCalled();

    await user.type(screen.getByLabelText("초기 선불 잔액"), "1.5");
    await user.click(screen.getByRole("button", { name: "협력사 등록하기" }));
    expect(screen.getByRole("alert")).toHaveTextContent("초기 선불 잔액");
    expect(createStorePartnerMock).not.toHaveBeenCalled();

    cleanup();
    setUrl("/store/partners/new");
    createStorePartnerMock.mockResolvedValue(partnerA);
    render(<Workspace><StorePartnerRegistrationForm /></Workspace>);
    await screen.findByRole("button", { name: "협력사 등록하기" });
    await user.type(screen.getByLabelText("협력사명"), "  후불 협력사  ");
    await user.click(screen.getByRole("radio", { name: "단체" }));
    await user.click(screen.getByRole("radio", { name: "후불" }));
    await user.type(screen.getByLabelText("대표자 전화번호 (선택)"), " 010-9876-5432 ");
    await user.type(screen.getByLabelText("대표자 이메일 (선택)"), " owner@example.com ");
    await user.click(screen.getByRole("button", { name: "협력사 등록하기" }));

    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith(`/store/meal-usages/months?mealContractId=${partnerA.mealContractId}`));
    expect(createStorePartnerMock).toHaveBeenCalledWith({
      partnerName: "후불 협력사",
      partnerKind: "ORGANIZATION",
      paymentType: "POSTPAID",
      initialPrepaidBalanceMinor: 0,
      qrSelectable: true,
      representativePhone: "010-9876-5432",
      representativeEmail: "owner@example.com",
    }, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  });

  it("retries an ambiguous registration with the same immutable key and upserts the selected ledger", async () => {
    const user = userEvent.setup();
    const createdPartner = { ...partnerB, partnerDisplayName: "새 협력사" };
    createStorePartnerMock
      .mockRejectedValueOnce(new TypeError("network unavailable"))
      .mockResolvedValueOnce(createdPartner);
    render(<Workspace><PartnerProbe /><StorePartnerRegistrationForm /></Workspace>);

    await screen.findByRole("button", { name: "협력사 등록하기" });
    await user.type(screen.getByLabelText("협력사명"), "새 협력사");
    await user.click(screen.getByRole("radio", { name: "단체" }));
    await user.click(screen.getByRole("radio", { name: "후불" }));
    await user.click(screen.getByRole("button", { name: "협력사 등록하기" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("같은 요청을 다시 시도");
    expect(createStorePartnerMock).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole("button", { name: "같은 요청 다시 시도" }));
    await waitFor(() => expect(createStorePartnerMock).toHaveBeenCalledTimes(2));
    expect(createStorePartnerMock).toHaveBeenNthCalledWith(2, {
      partnerName: "새 협력사",
      partnerKind: "ORGANIZATION",
      paymentType: "POSTPAID",
      initialPrepaidBalanceMinor: 0,
      qrSelectable: true,
      representativePhone: null,
      representativeEmail: null,
    }, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith(`/store/meal-usages/months?mealContractId=${createdPartner.mealContractId}`));
    expect(screen.getByTestId("partner-probe")).toHaveTextContent(createdPartner.mealContractId);
    expect(screen.queryByText(createdPartner.mealContractId)).not.toBeInTheDocument();
  });

  it("does not auto-retry or offer a new key after a 409 conflict", async () => {
    const user = userEvent.setup();
    createStorePartnerMock.mockRejectedValue(new ApiError(409, "IDEMPOTENCY_KEY_REUSED"));
    render(<Workspace><StorePartnerRegistrationForm /></Workspace>);

    await screen.findByRole("button", { name: "협력사 등록하기" });
    await user.type(screen.getByLabelText("협력사명"), "충돌 협력사");
    await user.click(screen.getByRole("radio", { name: "단체" }));
    await user.click(screen.getByRole("radio", { name: "후불" }));
    await user.click(screen.getByRole("button", { name: "협력사 등록하기" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("협력사 목록에서 결과를 확인");
    expect(createStorePartnerMock).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("button", { name: "같은 요청 다시 시도" })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "마이페이지에서 확인" })).toHaveAttribute("href", "/store/profile");
    expect(screen.getByLabelText("협력사명")).toBeDisabled();
  });

  it("validates optional representative contacts without submitting malformed values", async () => {
    const user = userEvent.setup();
    render(<Workspace><StorePartnerRegistrationForm /></Workspace>);

    await screen.findByRole("button", { name: "협력사 등록하기" });
    await user.type(screen.getByLabelText("협력사명"), "연락처 검증 협력사");
    await user.click(screen.getByRole("radio", { name: "단체" }));
    await user.click(screen.getByRole("radio", { name: "후불" }));
    await user.type(screen.getByLabelText("대표자 전화번호 (선택)"), "전화번호 아님");
    await user.click(screen.getByRole("button", { name: "협력사 등록하기" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("대표자 전화번호");
    expect(createStorePartnerMock).not.toHaveBeenCalled();

    await user.clear(screen.getByLabelText("대표자 전화번호 (선택)"));
    await user.type(screen.getByLabelText("대표자 이메일 (선택)"), "invalid-email");
    await user.click(screen.getByRole("button", { name: "협력사 등록하기" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("대표자 이메일");
    expect(createStorePartnerMock).not.toHaveBeenCalled();
  });
});
