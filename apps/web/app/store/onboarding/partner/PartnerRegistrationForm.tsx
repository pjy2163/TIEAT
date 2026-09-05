"use client";

import { FormEvent, useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  ApiError,
  getStoreOnboardingStatus,
  registerFirstPartner,
  skipFirstPartnerRegistration,
  type FirstPartnerRegistration,
} from "@/lib/store-api";
import { signupStyles } from "../../signup/SignupForm.styles";
import { StorePartnerKindFields } from "../../partners/StorePartnerKindFields";
import { StorePartnerPaymentFields } from "../../partners/StorePartnerPaymentFields";
import type { PartnerKind } from "@/lib/partner-kind";

type ViewState = "loading" | "ready" | "error";
const DEFAULT_QR_SELECTABLE = true;

export function PartnerRegistrationForm() {
  const router = useRouter();
  const [viewState, setViewState] = useState<ViewState>("loading");
  const [partnerName, setPartnerName] = useState("");
  const [partnerKind, setPartnerKind] = useState<PartnerKind | "">("");
  const [paymentType, setPaymentType] = useState<FirstPartnerRegistration["paymentType"] | null>(null);
  const [initialPrepaidBalanceMinor, setInitialPrepaidBalanceMinor] = useState("");
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
    if (partnerKind !== "INDIVIDUAL" && partnerKind !== "ORGANIZATION") {
      setErrorMessage("협력사 유형을 선택해 주세요.");
      return;
    }
    if (paymentType === null) {
      setErrorMessage("결제 유형을 선택해 주세요.");
      return;
    }
    const rawInitialBalance = initialPrepaidBalanceMinor.replaceAll(",", "").trim();
    if (paymentType === "PREPAID_WITH_RECEIVABLE_OVERFLOW"
      && (rawInitialBalance.length === 0 || !/^\d+$/.test(rawInitialBalance))) {
      setErrorMessage("초기 선불 잔액을 0 이상의 정수로 입력해 주세요.");
      return;
    }
    const initialBalance = paymentType === "POSTPAID" ? 0 : Number(rawInitialBalance);
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
        partnerKind,
        paymentType,
        initialPrepaidBalanceMinor: initialBalance,
        qrSelectable: DEFAULT_QR_SELECTABLE,
      });
      setSuccessMessage(
        result.created
          ? "첫 협력사를 등록했습니다. 장부로 이동합니다."
          : "협력사 등록이 이미 완료되어 장부로 이동합니다.",
      );
      if (result.created && result.mealContractId) {
        router.replace(`/store/meal-usages/months?mealContractId=${encodeURIComponent(result.mealContractId)}`);
      } else {
        router.replace("/store/meal-usages");
      }
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

  async function skipPartnerRegistration() {
    setIsSubmitting(true);
    setErrorMessage(null);
    try {
      await skipFirstPartnerRegistration();
      router.replace("/store/meal-usages");
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        router.replace("/store/login?next=/store/onboarding/partner");
      } else {
        setErrorMessage("협력사 등록을 건너뛰지 못했습니다. 다시 시도해 주세요.");
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
        <p className={signupStyles.description}>등록이 끝나면 해당 협력사의 월별 장부로 이동합니다. 입력한 결제 조건은 등록 시 함께 저장되며, 등록 전까지 자유롭게 선택할 수 있습니다. 이 설명은 나중에 자유롭게 변경 가능합니다.</p>

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

          <StorePartnerKindFields
            disabled={isSubmitting}
            onPartnerKindChange={setPartnerKind}
            partnerKind={partnerKind}
          />

          <StorePartnerPaymentFields
            balanceInputId="initialPrepaidBalanceMinor"
            disabled={isSubmitting}
            initialPrepaidBalanceMinor={initialPrepaidBalanceMinor}
            onInitialPrepaidBalanceMinorChange={setInitialPrepaidBalanceMinor}
            onPaymentTypeChange={setPaymentType}
            paymentType={paymentType}
          />

          {errorMessage ? <p className={signupStyles.error} role="alert">{errorMessage}</p> : null}
          {successMessage ? <p className={signupStyles.success} role="status">{successMessage}</p> : null}
          <div className={signupStyles.actions}>
            <button className={signupStyles.secondaryButton} type="button" disabled={isSubmitting} onClick={skipPartnerRegistration}>
              나중에 협력사 추가
            </button>
            <button className={signupStyles.button} type="submit" disabled={isSubmitting}>
              {isSubmitting ? "처리 중…" : "협력사 등록하기"}
            </button>
          </div>
        </form>
      </section>
    </main>
  );
}
