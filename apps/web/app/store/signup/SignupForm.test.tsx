import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, searchStorePlaces, signUpStoreAccount } from "@/lib/store-api";
import { SignupForm } from "./SignupForm";

const replace = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace }),
}));

vi.mock("@/lib/store-api", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/lib/store-api")>();
  return { ...original, searchStorePlaces: vi.fn(), signUpStoreAccount: vi.fn() };
});

const searchStorePlacesMock = vi.mocked(searchStorePlaces);
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
  it("shows Kakao name, address, and category then registers only the selected name", async () => {
    const user = userEvent.setup();
    searchStorePlacesMock.mockResolvedValue([{
      placeId: "26338954",
      storeDisplayName: "TIEAT 강남점",
      address: "서울 강남구 테헤란로 123",
      category: "음식점 > 한식",
    }]);
    signUpStoreAccountMock.mockResolvedValue({ onboardingStatus: "PARTNER_REQUIRED", legacy: false });
    render(<SignupForm />);

    await fillAccountStep(user);
    await user.type(screen.getByLabelText("가게명 검색"), "TIEAT");
    await user.click(screen.getByRole("button", { name: "검색" }));
    await user.click(await screen.findByRole("button", { name: /TIEAT 강남점/ }));
    await user.click(screen.getByRole("button", { name: "계정 만들기" }));

    await waitFor(() => expect(searchStorePlacesMock).toHaveBeenCalledWith({
      inviteCode: "pilot-code",
      query: "TIEAT",
    }));
    await waitFor(() => expect(signUpStoreAccountMock).toHaveBeenCalledWith({
      inviteCode: "pilot-code",
      loginId: "store-hk",
      password: "correct-password",
      manualStoreName: "TIEAT 강남점",
    }));
    expect(screen.getByRole("button", { name: /TIEAT 강남점/ })).toHaveTextContent("서울 강남구 테헤란로 123 · 음식점 > 한식");
    expect(JSON.stringify(signUpStoreAccountMock.mock.calls)).not.toContain("placeId");
    expect(document.querySelector("img")).toBeNull();
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/store/onboarding/partner"));
  });

  it("keeps the no-result direct-input path and maps an invalid invite on signup", async () => {
    const user = userEvent.setup();
    searchStorePlacesMock.mockResolvedValue([]);
    signUpStoreAccountMock.mockRejectedValue(new ApiError(403, "ONBOARDING_INVITE_INVALID"));
    render(<SignupForm />);

    await fillAccountStep(user);
    await user.type(screen.getByLabelText("가게명 검색"), "새 가게");
    await user.click(screen.getByRole("button", { name: "검색" }));
    expect(await screen.findByText("검색 결과가 없습니다. 새 가게명을 직접 입력해 등록할 수 있습니다.")).toBeVisible();
    await user.type(screen.getByLabelText("가게명 직접 입력"), "새 가게");
    await user.click(screen.getByRole("button", { name: "계정 만들기" }));

    await waitFor(() => expect(signUpStoreAccountMock).toHaveBeenCalledWith({
      inviteCode: "pilot-code",
      loginId: "store-hk",
      password: "correct-password",
      manualStoreName: "새 가게",
    }));
    expect(await screen.findByRole("alert")).toHaveTextContent("초대 코드를 확인해 주세요.");
  });

  it("offers direct input when Kakao search is unavailable without exposing a logo path", async () => {
    const user = userEvent.setup();
    searchStorePlacesMock.mockRejectedValue(new ApiError(503, "STORE_PLACE_SEARCH_UNAVAILABLE"));
    render(<SignupForm />);

    await fillAccountStep(user);
    await user.type(screen.getByLabelText("가게명 검색"), "TIEAT");
    await user.click(screen.getByRole("button", { name: "검색" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("장소 검색을 사용할 수 없습니다. 가게명을 직접 입력해 주세요.");
    expect(screen.getByLabelText("가게명 직접 입력")).toBeVisible();
    expect(document.querySelector("img")).toBeNull();
  });
});
