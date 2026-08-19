import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, searchStoreCatalog, signUpStoreAccount } from "@/lib/store-api";
import { SignupForm } from "./SignupForm";

const replace = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace }),
}));

vi.mock("@/lib/store-api", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/lib/store-api")>();
  return { ...original, searchStoreCatalog: vi.fn(), signUpStoreAccount: vi.fn() };
});

const searchStoreCatalogMock = vi.mocked(searchStoreCatalog);
const signUpStoreAccountMock = vi.mocked(signUpStoreAccount);

afterEach(() => {
  cleanup();
  vi.resetAllMocks();
});

async function fillAccountStep(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText("초대 코드"), "pilot-code");
  await user.type(screen.getByLabelText("로그인 ID"), "store-hk");
  await user.type(screen.getByLabelText("비밀번호"), "correct-password");
  await user.click(screen.getByRole("button", { name: "가게 설정으로" }));
}

describe("SignupForm", () => {
  it("creates an account from server-derived catalog metadata and continues to partner registration", async () => {
    const user = userEvent.setup();
    searchStoreCatalogMock.mockResolvedValue([{
      catalogEntryId: "00000000-0000-0000-0000-000000000001",
      storeDisplayName: "TIEAT 강남점",
      brandDisplayName: "TIEAT",
      logoPath: "/logos/tieat.svg",
    }]);
    signUpStoreAccountMock.mockResolvedValue({ onboardingStatus: "PARTNER_REQUIRED", legacy: false });
    render(<SignupForm />);

    await fillAccountStep(user);
    await user.type(screen.getByLabelText("가게명 검색"), "TIEAT");
    await user.click(screen.getByRole("button", { name: "검색" }));
    await user.click(await screen.findByRole("button", { name: /TIEAT 강남점/ }));
    await user.click(screen.getByRole("button", { name: "계정 만들기" }));

    await waitFor(() => expect(signUpStoreAccountMock).toHaveBeenCalledWith({
      inviteCode: "pilot-code",
      loginId: "store-hk",
      password: "correct-password",
      catalogEntryId: "00000000-0000-0000-0000-000000000001",
    }));
    expect(JSON.stringify(signUpStoreAccountMock.mock.calls)).not.toContain("storeId");
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/store/onboarding/partner"));
    expect(document.querySelector("img")).toHaveAttribute("src", "/logos/tieat.svg");
  });

  it("keeps a manual no-result path and shows the invite-code recovery message", async () => {
    const user = userEvent.setup();
    searchStoreCatalogMock.mockResolvedValue([]);
    signUpStoreAccountMock.mockRejectedValue(new ApiError(403, "ONBOARDING_INVITE_INVALID"));
    render(<SignupForm />);

    await fillAccountStep(user);
    await user.type(screen.getByLabelText("가게명 검색"), "새 가게");
    await user.click(screen.getByRole("button", { name: "검색" }));
    expect(await screen.findByText("검색 결과가 없습니다. 새 가게명을 직접 입력해 등록할 수 있습니다.")).toBeVisible();
    await user.type(screen.getByLabelText("새 가게명"), "새 가게");
    await user.click(screen.getByRole("button", { name: "계정 만들기" }));

    await waitFor(() => expect(signUpStoreAccountMock).toHaveBeenCalledWith({
      inviteCode: "pilot-code",
      loginId: "store-hk",
      password: "correct-password",
      manualStoreName: "새 가게",
    }));
    expect(await screen.findByRole("alert")).toHaveTextContent("초대 코드를 확인해 주세요.");
  });

  it("shows a local letter fallback when the catalog has no logo", async () => {
    const user = userEvent.setup();
    searchStoreCatalogMock.mockResolvedValue([{
      catalogEntryId: "00000000-0000-0000-0000-000000000002",
      storeDisplayName: "로고 없는 가게",
      brandDisplayName: "브랜드 B",
      logoPath: null,
    }]);
    render(<SignupForm />);

    await fillAccountStep(user);
    await user.type(screen.getByLabelText("가게명 검색"), "브랜드");
    await user.click(screen.getByRole("button", { name: "검색" }));

    expect(await screen.findByRole("button", { name: /로고 없는 가게/ })).toHaveTextContent("브");
  });
});
