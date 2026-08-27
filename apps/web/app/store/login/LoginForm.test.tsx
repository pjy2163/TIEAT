import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, getStoreOnboardingStatus, login } from "@/lib/store-api";
import { LoginForm } from "./LoginForm";

const replace = vi.fn();
let nextValue: string | null = null;

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace }),
  useSearchParams: () => new URLSearchParams(nextValue ? { next: nextValue } : undefined),
}));

vi.mock("@/lib/store-api", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/lib/store-api")>();
  return { ...original, getStoreOnboardingStatus: vi.fn(), login: vi.fn() };
});

const loginMock = vi.mocked(login);
const onboardingStatusMock = vi.mocked(getStoreOnboardingStatus);

beforeEach(() => {
  onboardingStatusMock.mockResolvedValue({ onboardingStatus: "COMPLETE", legacy: false });
});

afterEach(() => {
  cleanup();
  nextValue = null;
  vi.resetAllMocks();
});

async function fillCredentials(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText("로그인 ID"), "store-hk");
  await user.type(screen.getByLabelText("비밀번호"), "correct-password");
}

describe("LoginForm", () => {
  it("submits credentials then only replaces with the allowlisted next path", async () => {
    const user = userEvent.setup();
    nextValue = "/store/meal-usages";
    loginMock.mockResolvedValue(undefined);
    render(<LoginForm />);

    await fillCredentials(user);
    await user.click(screen.getByRole("button", { name: "로그인" }));

    expect(loginMock).toHaveBeenCalledWith("store-hk", "correct-password", false);
    expect(screen.getByRole("checkbox", { name: "이 브라우저에서 로그인 유지" })).not.toBeChecked();
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/store/meal-usages"));
  });

  it("opts into remembered login only when the native checkbox is checked", async () => {
    const user = userEvent.setup();
    nextValue = "/store/profile";
    loginMock.mockResolvedValue(undefined);
    render(<LoginForm />);

    await fillCredentials(user);
    await user.click(screen.getByRole("checkbox", { name: "이 브라우저에서 로그인 유지" }));
    await user.click(screen.getByRole("button", { name: "로그인" }));

    expect(loginMock).toHaveBeenCalledWith("store-hk", "correct-password", true);
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/store/profile"));
  });

  it("rejects an unallowlisted next value", async () => {
    const user = userEvent.setup();
    nextValue = "https://example.test";
    loginMock.mockResolvedValue(undefined);
    render(<LoginForm />);

    await fillCredentials(user);
    await user.click(screen.getByRole("button", { name: "로그인" }));

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/store/meal-usages"));
  });

  it("allows the POS settlement route as an authenticated store destination", async () => {
    const user = userEvent.setup();
    nextValue = "/store/pos-settlements";
    loginMock.mockResolvedValue(undefined);
    render(<LoginForm />);

    await fillCredentials(user);
    await user.click(screen.getByRole("button", { name: "로그인" }));

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/store/pos-settlements"));
  });

  it("allows the monthly ledger route as an authenticated store destination", async () => {
    const user = userEvent.setup();
    nextValue = "/store/meal-usages/months";
    loginMock.mockResolvedValue(undefined);
    render(<LoginForm />);

    await fillCredentials(user);
    await user.click(screen.getByRole("button", { name: "로그인" }));

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/store/meal-usages/months"));
  });

  it("guides a redirected visitor to log in before returning to the requested screen", () => {
    nextValue = "/store/pos-settlements";
    render(<LoginForm />);

    expect(screen.getByRole("status")).toHaveTextContent("이 화면은 로그인이 필요합니다. 로그인해 주세요.");
  });

  it("resumes first-partner registration before an otherwise safe next destination", async () => {
    const user = userEvent.setup();
    nextValue = "/store/meal-usages/months";
    loginMock.mockResolvedValue(undefined);
    onboardingStatusMock.mockResolvedValue({ onboardingStatus: "PARTNER_REQUIRED", legacy: false });
    render(<LoginForm />);

    await fillCredentials(user);
    await user.click(screen.getByRole("button", { name: "로그인" }));

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/store/onboarding/partner"));
  });

  it("shows a specific authentication failure message", async () => {
    const user = userEvent.setup();
    loginMock.mockRejectedValue(new ApiError(401, "AUTHENTICATION_FAILED"));
    render(<LoginForm />);

    await fillCredentials(user);
    await user.click(screen.getByRole("button", { name: "로그인" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("로그인 정보를 다시 확인해 주세요.");
  });

  it("keeps inputs and disables submission while login is pending, then shows generic failure", async () => {
    const user = userEvent.setup();
    let rejectLogin: (error: Error) => void = () => undefined;
    loginMock.mockImplementationOnce(() => new Promise((_, reject) => {
      rejectLogin = reject as (error: Error) => void;
    }));
    render(<LoginForm />);

    await fillCredentials(user);
    await user.click(screen.getByRole("button", { name: "로그인" }));

    expect(screen.getByRole("button", { name: "로그인 중…" })).toBeDisabled();
    expect(screen.getByLabelText("로그인 ID")).toHaveValue("store-hk");
    expect(screen.getByLabelText("비밀번호")).toHaveValue("correct-password");

    rejectLogin(new Error("network unavailable"));

    expect(await screen.findByRole("alert")).toHaveTextContent("로그인에 실패했습니다. 잠시 후 다시 시도해 주세요.");
    expect(screen.getByRole("button", { name: "로그인" })).toBeEnabled();
  });

  it("links a new store to the signup flow", () => {
    render(<LoginForm />);

    expect(screen.getByRole("link", { name: "매장 계정 만들기" })).toHaveAttribute("href", "/store/signup");
  });
});
