import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/store-api";
import { StoreLogoutButton } from "./StoreLogoutButton";

const navigation = vi.hoisted(() => ({ replace: vi.fn() }));
const logoutMock = vi.hoisted(() => ({ logoutStoreSession: vi.fn() }));

vi.mock("next/navigation", () => ({
  useRouter: () => navigation,
}));

vi.mock("@/lib/store-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/store-api")>();
  return { ...actual, logoutStoreSession: logoutMock.logoutStoreSession };
});

beforeEach(() => {
  vi.clearAllMocks();
  logoutMock.logoutStoreSession.mockResolvedValue(undefined);
});

afterEach(() => {
  cleanup();
});

describe("StoreLogoutButton", () => {
  it("logs out through the session API and moves to the login screen", async () => {
    const user = userEvent.setup();
    render(<StoreLogoutButton />);

    await user.click(screen.getByRole("button", { name: "로그아웃" }));

    await waitFor(() => expect(logoutMock.logoutStoreSession).toHaveBeenCalledTimes(1));
    expect(navigation.replace).toHaveBeenCalledWith("/store/login");
  });

  it("shows a retryable error when logout fails and handles an expired session", async () => {
    const user = userEvent.setup();
    logoutMock.logoutStoreSession.mockRejectedValueOnce(new ApiError(503, "SERVICE_UNAVAILABLE"));
    render(<StoreLogoutButton />);

    await user.click(screen.getByRole("button", { name: "로그아웃" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("로그아웃하지 못했습니다. 잠시 후 다시 시도해 주세요.");
    expect(screen.getByRole("button", { name: "로그아웃" })).not.toBeDisabled();

    logoutMock.logoutStoreSession.mockRejectedValueOnce(new ApiError(401, "AUTHENTICATION_REQUIRED"));
    await user.click(screen.getByRole("button", { name: "로그아웃" }));
    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/store/login"));
  });
});
