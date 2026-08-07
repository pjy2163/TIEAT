"use client";

import { FormEvent, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { ApiError, login } from "@/lib/store-api";
import { loginStyles } from "./LoginForm.styles";

function safeNext(value: string | null): string {
  return value === "/store/meal-usages" ? value : "/store/meal-usages";
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

    setIsSubmitting(true);
    setErrorMessage(null);
    try {
      await login(loginId, password);
      router.replace(safeNext(searchParams.get("next")));
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        setErrorMessage("로그인 정보를 다시 확인해 주세요.");
      } else {
        setErrorMessage("로그인에 실패했습니다. 잠시 후 다시 시도해 주세요.");
      }
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <main className={loginStyles.page}>
      <section className={loginStyles.card} aria-labelledby="login-title">
        <p className={loginStyles.eyebrow}>TIEAT STORE</p>
        <h1 id="login-title" className={loginStyles.title}>매장 태블릿 로그인</h1>
        <p className={loginStyles.description}>확인 대기 거래를 보려면 매장 계정으로 로그인해 주세요.</p>

        <form className={loginStyles.form} onSubmit={onSubmit}>
          <div className={loginStyles.field}>
            <label className={loginStyles.label} htmlFor="loginId">로그인 ID</label>
            <input className={loginStyles.input} id="loginId" name="loginId" autoComplete="username" required />
          </div>
          <div className={loginStyles.field}>
            <label className={loginStyles.label} htmlFor="password">비밀번호</label>
            <input className={loginStyles.input} id="password" name="password" type="password" autoComplete="current-password" required />
          </div>
          {errorMessage ? <p className={loginStyles.error} role="alert">{errorMessage}</p> : null}
          <button className={loginStyles.button} type="submit" disabled={isSubmitting}>
            {isSubmitting ? "로그인 중…" : "로그인"}
          </button>
        </form>
      </section>
    </main>
  );
}
