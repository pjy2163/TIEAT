"use client";

import { FormEvent, useEffect, useRef, useState } from "react";
import {
  createPublicMealUsage,
  getPublicMealUsageQrContext,
  PublicQrApiError,
  type PublicMealUsageQrContext,
  type PublicPendingMealUsage,
} from "@/lib/public-qr-api";
import { mealUsageQrFormStyles } from "./MealUsageQrForm.styles";

type ContextState = "loading" | "ready" | "invalid" | "error";

const MAX_AMOUNT_MINOR = 1_000_000;
const amountFormatter = new Intl.NumberFormat("ko-KR", {
  style: "currency",
  currency: "KRW",
  maximumFractionDigits: 0,
});

function amountError(amountInput: string): string | null {
  if (!/^\d+$/.test(amountInput)) return "금액은 원 단위의 양의 정수로 입력해 주세요.";
  const amount = Number(amountInput);
  if (!Number.isSafeInteger(amount) || amount <= 0 || amount > MAX_AMOUNT_MINOR) {
    return "금액은 1원 이상 1,000,000원 이하로 입력해 주세요.";
  }
  return null;
}

function submissionError(error: unknown): string {
  if (error instanceof PublicQrApiError && error.status === 404) {
    return "이 QR 또는 선택한 협력사는 지금 사용할 수 없습니다. QR을 다시 확인해 주세요.";
  }
  if (error instanceof PublicQrApiError && error.status === 409) {
    return "같은 요청의 내용이 달라 제출할 수 없습니다. 입력값을 확인한 뒤 다시 시도해 주세요.";
  }
  if (error instanceof PublicQrApiError && error.status === 429) {
    return "요청이 잠시 많습니다. 잠시 후 다시 시도해 주세요.";
  }
  if (error instanceof PublicQrApiError && error.status === 400) {
    return "입력값을 다시 확인해 주세요.";
  }
  return "제출 결과를 확인하지 못했습니다. 입력은 유지되며 같은 버튼으로 다시 시도할 수 있습니다.";
}

export function MealUsageQrForm({ token }: { token: string }) {
  const [reloadAttempt, setReloadAttempt] = useState(0);
  return (
    <MealUsageQrFormForToken
      key={`${token}:${reloadAttempt}`}
      token={token}
      onRetry={() => setReloadAttempt((attempt) => attempt + 1)}
    />
  );
}

function MealUsageQrFormForToken({ token, onRetry }: { token: string; onRetry: () => void }) {
  const [contextState, setContextState] = useState<ContextState>("loading");
  const [context, setContext] = useState<PublicMealUsageQrContext | null>(null);
  const [selectedMealContractId, setSelectedMealContractId] = useState("");
  const [amountInput, setAmountInput] = useState("");
  const [formError, setFormError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [submittedUsage, setSubmittedUsage] = useState<PublicPendingMealUsage | null>(null);
  const idempotencyKeyRef = useRef<string | null>(null);

  useEffect(() => {
    let active = true;
    void getPublicMealUsageQrContext(token)
      .then((nextContext) => {
        if (!active) return;
        setContext(nextContext);
        setContextState("ready");
      })
      .catch((error: unknown) => {
        if (!active) return;
        setContextState(error instanceof PublicQrApiError && error.status === 404 ? "invalid" : "error");
      });
    return () => {
      active = false;
    };
  }, [token]);

  const resetRequestKey = () => {
    idempotencyKeyRef.current = null;
    setFormError(null);
  };

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!context || isSubmitting) return;
    if (!selectedMealContractId) {
      setFormError("협력사를 선택해 주세요.");
      return;
    }
    const validationError = amountError(amountInput);
    if (validationError) {
      setFormError(validationError);
      return;
    }
    if (typeof globalThis.crypto?.randomUUID !== "function") {
      setFormError("이 브라우저에서는 안전한 제출 키를 만들 수 없습니다.");
      return;
    }
    const idempotencyKey = idempotencyKeyRef.current ?? globalThis.crypto.randomUUID();
    idempotencyKeyRef.current = idempotencyKey;
    setIsSubmitting(true);
    setFormError(null);
    try {
      const usage = await createPublicMealUsage(token, idempotencyKey, selectedMealContractId, Number(amountInput));
      setSubmittedUsage(usage);
    } catch (error) {
      setFormError(submissionError(error));
    } finally {
      setIsSubmitting(false);
    }
  };

  if (contextState === "loading") {
    return <StatePanel title="QR 정보를 불러오는 중" description="잠시만 기다려 주세요." busy />;
  }
  if (contextState === "invalid") {
    return <StatePanel title="이 QR을 사용할 수 없습니다" description="QR을 다시 확인하거나 매장 직원에게 문의해 주세요." />;
  }
  if (contextState === "error" || !context) {
    return <StatePanel title="QR 정보를 불러오지 못했습니다" description="네트워크 상태를 확인한 뒤 다시 시도해 주세요." onRetry={onRetry} />;
  }
  if (submittedUsage) {
    return (
      <main className={mealUsageQrFormStyles.page}>
        <section className={mealUsageQrFormStyles.container} aria-live="polite">
          <div className={mealUsageQrFormStyles.success} role="status">
            <p className={mealUsageQrFormStyles.successTitle}>확인 대기 요청을 보냈습니다</p>
            <p className={mealUsageQrFormStyles.successAmount}>{amountFormatter.format(submittedUsage.amountMinor)}</p>
            <p className={mealUsageQrFormStyles.successDescription}>
              {context.storeDisplayName} 매장 직원이 확인하면 최종 처리됩니다. 이 화면에서는 이용 내역이나 잔액을 표시하지 않습니다.
            </p>
          </div>
        </section>
      </main>
    );
  }

  return (
    <main className={mealUsageQrFormStyles.page}>
      <section className={mealUsageQrFormStyles.container} aria-labelledby="qr-meal-usage-title">
        <p className={mealUsageQrFormStyles.eyebrow}>TIEAT QR</p>
        <h1 id="qr-meal-usage-title" className={mealUsageQrFormStyles.title}>{context.storeDisplayName} 식대 입력</h1>
        <p className={mealUsageQrFormStyles.description}>협력사와 금액을 입력하면 매장 확인 대기 요청이 만들어집니다.</p>
        {context.partners.length === 0 ? (
          <div className={mealUsageQrFormStyles.state}>
            <h2 className={mealUsageQrFormStyles.stateTitle}>선택 가능한 협력사가 없습니다</h2>
            <p className={mealUsageQrFormStyles.stateDescription}>매장 직원에게 현재 식대 계약을 확인해 주세요.</p>
          </div>
        ) : (
          <form className={mealUsageQrFormStyles.card} onSubmit={(event) => void handleSubmit(event)}>
            <label className={mealUsageQrFormStyles.label} htmlFor="partner-contract">협력사</label>
            <select
              className={mealUsageQrFormStyles.select}
              disabled={isSubmitting}
              id="partner-contract"
              onChange={(event) => {
                setSelectedMealContractId(event.target.value);
                resetRequestKey();
              }}
              value={selectedMealContractId}
            >
              <option value="">협력사를 선택해 주세요</option>
              {context.partners.map((partner) => (
                <option key={partner.mealContractId} value={partner.mealContractId}>{partner.partnerDisplayName}</option>
              ))}
            </select>
            <label className={mealUsageQrFormStyles.label} htmlFor="amount-minor">금액</label>
            <input
              className={mealUsageQrFormStyles.input}
              disabled={isSubmitting}
              id="amount-minor"
              inputMode="numeric"
              max={MAX_AMOUNT_MINOR}
              min="1"
              onChange={(event) => {
                setAmountInput(event.target.value);
                resetRequestKey();
              }}
              pattern="[0-9]*"
              placeholder="예: 8500"
              type="text"
              value={amountInput}
            />
            <p className={mealUsageQrFormStyles.hint}>원 단위로 1원 이상 1,000,000원 이하를 입력해 주세요.</p>
            {formError ? <p className={mealUsageQrFormStyles.error} role="alert">{formError}</p> : null}
            <button className={mealUsageQrFormStyles.submit} disabled={isSubmitting} type="submit">
              {isSubmitting ? "요청 보내는 중…" : "확인 대기 요청 보내기"}
            </button>
          </form>
        )}
      </section>
    </main>
  );
}

function StatePanel({
  title,
  description,
  busy = false,
  onRetry,
}: {
  title: string;
  description: string;
  busy?: boolean;
  onRetry?: () => void;
}) {
  return (
    <main className={mealUsageQrFormStyles.page} aria-busy={busy}>
      <section className={`${mealUsageQrFormStyles.container} ${mealUsageQrFormStyles.state}`} aria-live="polite">
        <h1 className={mealUsageQrFormStyles.stateTitle}>{title}</h1>
        <p className={mealUsageQrFormStyles.stateDescription}>{description}</p>
        {onRetry ? <button className={mealUsageQrFormStyles.retry} onClick={onRetry} type="button">다시 시도</button> : null}
      </section>
    </main>
  );
}
