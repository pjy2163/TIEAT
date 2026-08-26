"use client";

import { FormEvent, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { ApiError, getStoreOnboardingStatus, login } from "@/lib/store-api";
import { StoreAuthField } from "../auth/StoreAuthField";
import { StoreAuthShell } from "../auth/StoreAuthShell";
import { storeAuthStyles } from "../auth/StoreAuth.styles";
import { loginStyles } from "./LoginForm.styles";

function safeNext(value: string | null): string {
  return value === "/store/profile" || value === "/store/meal-usages" || value === "/store/meal-usages/months" || value === "/store/pos-settlements"
    ? value
    : "/store/meal-usages";
}

export function LoginForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const loginId = String(form.get("loginId") ?? "");
    const password = String(form.get("password") ?? "");
    const rememberLogin = form.get("rememberLogin") === "on";

    setIsSubmitting(true);
    setErrorMessage(null);
    let sessionCreated = false;
    try {
      await login(loginId, password, rememberLogin);
      sessionCreated = true;
      const onboarding = await getStoreOnboardingStatus();
      router.replace(
        onboarding.onboardingStatus === "PARTNER_REQUIRED"
          ? "/store/onboarding/partner"
          : safeNext(searchParams.get("next")),
      );
    } catch (error) {
      if (!sessionCreated && error instanceof ApiError && error.status === 401) {
        setErrorMessage("로그인 정보를 다시 확인해 주세요.");
      } else if (sessionCreated) {
        setErrorMessage("로그인은 완료됐지만 초기 설정을 확인하지 못했습니다. 다시 시도해 주세요.");
      } else {
        setErrorMessage("로그인에 실패했습니다. 잠시 후 다시 시도해 주세요.");
      }
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <StoreAuthShell
      footer={<>처음 사용하시나요? <a className={storeAuthStyles.link} href="/store/signup">매장 계정 만들기</a></>}
      title="매장 로그인"
      titleId="login-title"
      width="narrow"
    >
      <form className={storeAuthStyles.form} onSubmit={onSubmit}>
        <StoreAuthField label="로그인 ID" htmlFor="loginId">
          <input className={storeAuthStyles.input} id="loginId" name="loginId" autoComplete="username" required />
        </StoreAuthField>
        <StoreAuthField label="비밀번호" htmlFor="password">
          <input className={storeAuthStyles.input} id="password" name="password" type="password" autoComplete="current-password" required />
        </StoreAuthField>
        <label className="flex items-center gap-2 text-sm text-[var(--text-secondary)]" htmlFor="rememberLogin">
          <input className="h-4 w-4 accent-[var(--accent)]" id="rememberLogin" name="rememberLogin" type="checkbox" />
          이 브라우저에서 로그인 유지
        </label>
        {errorMessage ? <p className={storeAuthStyles.error} role="alert">{errorMessage}</p> : null}
        <button className={loginStyles.button} type="submit" disabled={isSubmitting}>
          {isSubmitting ? "로그인 중…" : "로그인"}
        </button>
      </form>
    </StoreAuthShell>
  );
}
