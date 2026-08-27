"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { ApiError, logoutStoreSession } from "@/lib/store-api";
import { storeProfileStyles as styles } from "./StoreProfileView.styles";

export function StoreLogoutButton() {
  const router = useRouter();
  const [isLoggingOut, setIsLoggingOut] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  async function handleLogout() {
    if (isLoggingOut) return;
    setIsLoggingOut(true);
    setErrorMessage(null);
    try {
      await logoutStoreSession();
      router.replace("/store/login");
    } catch (error: unknown) {
      if (error instanceof ApiError && error.status === 401) {
        router.replace("/store/login");
        return;
      }
      setErrorMessage("로그아웃하지 못했습니다. 잠시 후 다시 시도해 주세요.");
    } finally {
      setIsLoggingOut(false);
    }
  }

  return (
    <div className={styles.logoutArea}>
      <button
        aria-busy={isLoggingOut}
        className={styles.logoutButton}
        disabled={isLoggingOut}
        onClick={handleLogout}
        type="button"
      >
        {isLoggingOut ? "로그아웃 중…" : "로그아웃"}
      </button>
      {errorMessage ? <p className={styles.logoutError} role="alert">{errorMessage}</p> : null}
    </div>
  );
}
