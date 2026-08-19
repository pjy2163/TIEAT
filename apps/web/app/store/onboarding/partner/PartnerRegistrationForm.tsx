"use client";

import { FormEvent, useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  ApiError,
  getStoreOnboardingStatus,
  registerFirstPartner,
  type FirstPartnerRegistration,
} from "@/lib/store-api";
import { signupStyles } from "../../signup/SignupForm.styles";

type ViewState = "loading" | "ready" | "error";

export function PartnerRegistrationForm() {
  const router = useRouter();
  const [viewState, setViewState] = useState<ViewState>("loading");
  const [partnerName, setPartnerName] = useState("");
  const [paymentType, setPaymentType] = useState<FirstPartnerRegistration["paymentType"] | null>(null);
  const [initialPrepaidBalanceMinor, setInitialPrepaidBalanceMinor] = useState("");
  const [qrSelectable, setQrSelectable] = useState<boolean | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  const loadStatus = useCallback(async () => {
    try {
      const onboarding = await getStoreOnboardingStatus();
      if (onboarding.onboardingStatus === "COMPLETE") {
        router.replace("/store/meal-usages");
        return;
      }
      setViewState("ready");
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        router.replace("/store/login?next=/store/onboarding/partner");
        return;
      }
      if (error instanceof ApiError && error.status === 403) {
        setErrorMessage("협력사 등록 권한이 없습니다.");
      } else {
        setErrorMessage("초기 설정을 불러오지 못했습니다. 다시 시도해 주세요.");
      }
      setViewState("error");
    }
  }, [router]);

  useEffect(() => {
    const loadTimer = window.setTimeout(() => {
      void loadStatus();
    }, 0);
    return () => window.clearTimeout(loadTimer);
  }, [loadStatus]);

  function retryStatus() {
    setViewState("loading");
    setErrorMessage(null);
    void loadStatus();
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (paymentType === null) {
      setErrorMessage("결제 유형을 선택해 주세요.");
      return;
    }
    if (qrSelectable === null) {
      setErrorMessage("QR 검색 노출 여부를 선택해 주세요.");
      return;
    }

    const initialBalance = paymentType === "POSTPAID" ? 0 : Number(initialPrepaidBalanceMinor);
    if (!Number.isSafeInteger(initialBalance) || initialBalance < 0) {
      setErrorMessage("초기 선불 잔액을 0 이상의 정수로 입력해 주세요.");
      return;
    }

    setIsSubmitting(true);
    setErrorMessage(null);
    setSuccessMessage(null);
    try {
      const result = await registerFirstPartner({
        partnerName,
        paymentType,
        initialPrepaidBalanceMinor: initialBalance,
        qrSelectable,
      });
      setSuccessMessage(
        result.created
          ? "첫 협력사를 등록했습니다. 장부로 이동합니다."
          : "협력사 등록이 이미 완료되어 장부로 이동합니다.",
      );
      router.replace("/store/meal-usages");
    } catch (error) {
      if (error instanceof ApiError && error.status === 400) {
        setErrorMessage("입력 내용을 확인해 주세요.");
      } else if (error instanceof ApiError && error.status === 401) {
        router.replace("/store/login?next=/store/onboarding/partner");
      } else {
        setErrorMessage("협력사 등록에 실패했습니다. 다시 시도해 주세요.");
      }
    } finally {
      setIsSubmitting(false);
    }
  }

  if (viewState === "loading") {
    return (
      <main className={signupStyles.page}>
        <section className={signupStyles.card} aria-live="polite">
          <p className={signupStyles.description}>초기 설정을 확인하는 중…</p>
        </section>
      </main>
    );
  }

  if (viewState === "error") {
    return (
      <main className={signupStyles.page}>
        <section className={signupStyles.card} aria-labelledby="partner-load-error-title">
          <h1 id="partner-load-error-title" className={signupStyles.title}>협력사 등록을 열 수 없습니다</h1>
          {errorMessage ? <p className={`${signupStyles.error} mt-5`} role="alert">{errorMessage}</p> : null}
          <button className={`${signupStyles.button} mt-5`} type="button" onClick={retryStatus}>
            다시 시도
          </button>
        </section>
      </main>
    );
  }

  return (
    <main className={signupStyles.page}>
      <section className={signupStyles.card} aria-labelledby="partner-title">
        <p className={signupStyles.eyebrow}>TIEAT STORE</p>
        <h1 id="partner-title" className={signupStyles.title}>첫 협력사 등록하기</h1>
        <p className={signupStyles.description}>등록이 끝나면 매장 장부를 바로 사용할 수 있습니다.</p>

        <form className={signupStyles.form} onSubmit={submit}>
          <div className={signupStyles.field}>
            <label className={signupStyles.label} htmlFor="partnerName">협력사명</label>
            <input
              className={signupStyles.input}
              id="partnerName"
              value={partnerName}
              onChange={(event) => setPartnerName(event.target.value)}
              maxLength={100}
              required
            />
          </div>

          <fieldset className={signupStyles.field}>
            <legend className={signupStyles.label}>결제 유형</legend>
            <div className="grid gap-2 sm:grid-cols-2">
              <label className="flex min-h-12 cursor-pointer items-center gap-2 rounded-lg border border-[var(--border-strong)] px-3 text-sm text-[var(--text-primary)]">
                <input
                  type="radio"
                  name="paymentType"
                  checked={paymentType === "POSTPAID"}
                  onChange={() => setPaymentType("POSTPAID")}
                />
                후불
              </label>
              <label className="flex min-h-12 cursor-pointer items-center gap-2 rounded-lg border border-[var(--border-strong)] px-3 text-sm text-[var(--text-primary)]">
                <input
                  type="radio"
                  name="paymentType"
                  checked={paymentType === "PREPAID_WITH_RECEIVABLE_OVERFLOW"}
                  onChange={() => setPaymentType("PREPAID_WITH_RECEIVABLE_OVERFLOW")}
                />
                선불 후 미수금
              </label>
            </div>
          </fieldset>

          {paymentType === "PREPAID_WITH_RECEIVABLE_OVERFLOW" ? (
            <div className={signupStyles.field}>
              <label className={signupStyles.label} htmlFor="initialPrepaidBalanceMinor">초기 선불 잔액</label>
              <input
                className={signupStyles.input}
                id="initialPrepaidBalanceMinor"
                value={initialPrepaidBalanceMinor}
                onChange={(event) => setInitialPrepaidBalanceMinor(event.target.value)}
                type="number"
                min="0"
                step="1"
                inputMode="numeric"
                required
              />
              <p className={signupStyles.hint}>선불을 모두 사용하면 초과 사용액은 미수금으로 기록됩니다.</p>
            </div>
          ) : null}

          <fieldset className={signupStyles.field}>
            <legend className={signupStyles.label}>QR에서 협력사를 검색할 수 있나요?</legend>
            <div className="grid gap-2 sm:grid-cols-2">
              <label className="flex min-h-12 cursor-pointer items-center gap-2 rounded-lg border border-[var(--border-strong)] px-3 text-sm text-[var(--text-primary)]">
                <input
                  type="radio"
                  name="qrSelectable"
                  checked={qrSelectable === true}
                  onChange={() => setQrSelectable(true)}
                />
                예, 검색에 표시
              </label>
              <label className="flex min-h-12 cursor-pointer items-center gap-2 rounded-lg border border-[var(--border-strong)] px-3 text-sm text-[var(--text-primary)]">
                <input
                  type="radio"
                  name="qrSelectable"
                  checked={qrSelectable === false}
                  onChange={() => setQrSelectable(false)}
                />
                아니요, 나중에 설정
              </label>
            </div>
          </fieldset>

          {errorMessage ? <p className={signupStyles.error} role="alert">{errorMessage}</p> : null}
          {successMessage ? <p className={signupStyles.success} role="status">{successMessage}</p> : null}
          <button className={signupStyles.button} type="submit" disabled={isSubmitting}>
            {isSubmitting ? "협력사 등록 중…" : "협력사 등록하기"}
          </button>
        </form>
      </section>
    </main>
  );
}
