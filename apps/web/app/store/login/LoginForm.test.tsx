import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, login } from "@/lib/store-api";
import { LoginForm } from "./LoginForm";

const replace = vi.fn();
let nextValue: string | null = null;

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace }),
  useSearchParams: () => new URLSearchParams(nextValue ? { next: nextValue } : undefined),
}));

vi.mock("@/lib/store-api", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/lib/store-api")>();
  return { ...original, login: vi.fn() };
});

const loginMock = vi.mocked(login);

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

    expect(loginMock).toHaveBeenCalledWith("store-hk", "correct-password");
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/store/meal-usages"));
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


  it("allows the monthly ledger route as an authenticated store destination", async () => {
    const user = userEvent.setup();
    nextValue = "/store/meal-usages/months";
    loginMock.mockResolvedValue(undefined);
    render(<LoginForm />);

    await fillCredentials(user);
    await user.click(screen.getByRole("button", { name: "로그인" }));

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/store/meal-usages/months"));
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
});
