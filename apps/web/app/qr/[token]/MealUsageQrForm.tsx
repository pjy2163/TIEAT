"use client";

import { FormEvent, useCallback, useEffect, useRef, useState } from "react";
import {
  cancelPublicMealUsage,
  createPublicMealUsage,
  getPublicMealUsageRequest,
  getPublicMealUsageQrContext,
  PublicQrApiError,
  type PublicMealUsageQrContext,
  type PublicPendingMealUsage,
} from "@/lib/public-qr-api";
import { mealUsageQrFormStyles } from "./MealUsageQrForm.styles";

type ContextState = "loading" | "ready" | "invalid" | "error";

const MAX_AMOUNT_MINOR = 1_000_000;
const MAX_CUSTOMER_NAME_LENGTH = 80;
const SUCCESS_RESET_DELAY_SECONDS = 5;
const SUCCESS_RESET_DELAY_MILLIS = SUCCESS_RESET_DELAY_SECONDS * 1_000;
const REQUEST_RECOVERY_LIFETIME_MILLIS = 10 * 60 * 1_000;
const REQUEST_RECOVERY_STORAGE_KEY = "tieat.public-meal-usage-request.v1";
const PUBLIC_CLIENT_KEY_PREFIX = "tieat.public-qr-client-key.v1:";
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

function customerNameError(customerNameInput: string): string | null {
  const normalized = customerNameInput.trim();
  if (!normalized) return "고객 이름을 입력해 주세요.";
  if (normalized.length > MAX_CUSTOMER_NAME_LENGTH) return "고객 이름은 80자 이하로 입력해 주세요.";
  return null;
}

function normalizedPartnerSearch(value: string): string {
  return value.replace(/\s/g, "").toLocaleLowerCase("ko-KR");
}

type PendingRequestCapability = {
  mealUsageId: string;
  idempotencyKey: string;
  publicRequestKey: string;
};

type RequestRecovery = {
  version: 1;
  tokenFingerprint: string;
  savedAt: number;
  idempotencyKey: string | null;
  publicRequestKey: string | null;
  pending: PendingRequestCapability | null;
  selectedMealContractId: string;
  partnerSearch: string;
  customerNameInput: string;
  amountInput: string;
};

function encodeBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return window.btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}

function canCreatePublicRequestKey(): boolean {
  return typeof globalThis.crypto?.randomUUID === "function"
    && typeof globalThis.crypto?.getRandomValues === "function"
    && typeof globalThis.crypto?.subtle?.digest === "function";
}

function createPublicRequestKey(): string | null {
  if (!canCreatePublicRequestKey()) return null;
  const bytes = new Uint8Array(32);
  globalThis.crypto.getRandomValues(bytes);
  return encodeBase64Url(bytes);
}

async function tokenFingerprint(token: string): Promise<string | null> {
  if (typeof globalThis.crypto?.subtle?.digest !== "function") return null;
  const digest = await globalThis.crypto.subtle.digest("SHA-256", new TextEncoder().encode(token));
  return encodeBase64Url(new Uint8Array(digest));
}

function parseRequestRecovery(value: string | null): RequestRecovery | null {
  if (!value) return null;
  try {
    const parsed: unknown = JSON.parse(value);
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) return null;
    const recovery = parsed as Partial<RequestRecovery>;
    if (recovery.version !== 1
      || typeof recovery.tokenFingerprint !== "string"
      || !Number.isFinite(recovery.savedAt)
      || (recovery.idempotencyKey !== null && typeof recovery.idempotencyKey !== "string")
      || (recovery.publicRequestKey !== null && typeof recovery.publicRequestKey !== "string")
      || typeof recovery.selectedMealContractId !== "string"
      || typeof recovery.partnerSearch !== "string"
      || (recovery.customerNameInput !== undefined && typeof recovery.customerNameInput !== "string")
      || typeof recovery.amountInput !== "string") {
      return null;
    }
    if (recovery.pending !== null && (typeof recovery.pending !== "object"
      || recovery.pending === null
      || typeof recovery.pending.mealUsageId !== "string"
      || typeof recovery.pending.idempotencyKey !== "string"
      || typeof recovery.pending.publicRequestKey !== "string")) {
      return null;
    }
    return { ...recovery, customerNameInput: recovery.customerNameInput ?? "" } as RequestRecovery;
  } catch {
    return null;
  }
}

function submissionError(error: unknown): string {
  if (error instanceof PublicQrApiError && error.status === 503 && error.errorCode === "PUBLIC_QR_CREATION_PAUSED") {
    return "새 요청이 일시 중지되었습니다. 입력한 내용은 유지되니 매장 직원에게 재개를 요청해 주세요.";
  }
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
  const [partnerSearch, setPartnerSearch] = useState("");
  const [customerNameInput, setCustomerNameInput] = useState("");
  const [amountInput, setAmountInput] = useState("");
  const [formError, setFormError] = useState<string | null>(null);
  const [formNotice, setFormNotice] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isCancelling, setIsCancelling] = useState(false);
  const [isRecoveringRequest, setIsRecoveringRequest] = useState(false);
  const [submittedUsage, setSubmittedUsage] = useState<PublicPendingMealUsage | null>(null);
  const [recentPendingCapability, setRecentPendingCapability] = useState<PendingRequestCapability | null>(null);
  const [secondsUntilReset, setSecondsUntilReset] = useState(SUCCESS_RESET_DELAY_SECONDS);
  const idempotencyKeyRef = useRef<string | null>(null);
  const publicRequestKeyRef = useRef<string | null>(null);
  const publicClientKeyRef = useRef<string | null>(createPublicRequestKey());
  const requestFingerprintRef = useRef<string | null>(null);
  const [requestFingerprint, setRequestFingerprint] = useState<string | null>(null);
  const [recoveryHydrated, setRecoveryHydrated] = useState(false);

  const clearRequestRecovery = useCallback(() => {
    try {
      window.sessionStorage.removeItem(REQUEST_RECOVERY_STORAGE_KEY);
    } catch {
      // Session storage is only a recovery convenience; the server remains authoritative.
    }
  }, []);

  const saveRequestRecovery = useCallback((pending: PendingRequestCapability | null = recentPendingCapability) => {
    const fingerprint = requestFingerprintRef.current;
    if (!fingerprint) return;
    const recovery: RequestRecovery = {
      version: 1,
      tokenFingerprint: fingerprint,
      savedAt: Date.now(),
      idempotencyKey: idempotencyKeyRef.current,
      publicRequestKey: publicRequestKeyRef.current,
      pending,
      selectedMealContractId,
      partnerSearch,
      customerNameInput,
      amountInput,
    };
    try {
      window.sessionStorage.setItem(REQUEST_RECOVERY_STORAGE_KEY, JSON.stringify(recovery));
    } catch {
      // Session storage is only a recovery convenience; submitting the request still works.
    }
  }, [amountInput, customerNameInput, partnerSearch, recentPendingCapability, selectedMealContractId]);

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

  useEffect(() => {
    let active = true;
    void tokenFingerprint(token).then(async (fingerprint) => {
      if (!active) return;
      requestFingerprintRef.current = fingerprint;
      setRequestFingerprint(fingerprint);
      if (fingerprint) {
        const storageKey = `${PUBLIC_CLIENT_KEY_PREFIX}${fingerprint}`;
        let stored: string | null = null;
        try { stored = window.sessionStorage.getItem(storageKey); } catch { /* page-memory key remains usable */ }
        const clientKey = stored && /^[A-Za-z0-9_-]{43}$/.test(stored)
          ? stored : publicClientKeyRef.current ?? createPublicRequestKey();
        if (clientKey) {
          publicClientKeyRef.current = clientKey;
          try { window.sessionStorage.setItem(storageKey, clientKey); } catch { /* best effort */ }
        }
      }
      if (!fingerprint) {
        setRecoveryHydrated(true);
        return;
      }
      let savedRecovery: string | null = null;
      try { savedRecovery = window.sessionStorage.getItem(REQUEST_RECOVERY_STORAGE_KEY); } catch { /* best effort */ }
      const recovery = parseRequestRecovery(savedRecovery);
      if (!recovery
        || recovery.tokenFingerprint !== fingerprint
        || Date.now() - recovery.savedAt >= REQUEST_RECOVERY_LIFETIME_MILLIS) {
        clearRequestRecovery();
        setRecoveryHydrated(true);
        return;
      }
      setSelectedMealContractId(recovery.selectedMealContractId);
      setPartnerSearch(recovery.partnerSearch);
      setCustomerNameInput(recovery.customerNameInput);
      setAmountInput(recovery.amountInput);
      idempotencyKeyRef.current = recovery.idempotencyKey;
      publicRequestKeyRef.current = recovery.publicRequestKey;
      if (!recovery.pending) {
        setRecoveryHydrated(true);
        return;
      }

      setIsRecoveringRequest(true);
      try {
        const recoveredUsage = await getPublicMealUsageRequest(
          token,
          recovery.pending.mealUsageId,
          recovery.pending.idempotencyKey,
          recovery.pending.publicRequestKey
        );
        if (!active) return;
        if (recoveredUsage.status === "PENDING") {
          setRecentPendingCapability(recovery.pending);
          setSecondsUntilReset(SUCCESS_RESET_DELAY_SECONDS);
          setSubmittedUsage({ ...recoveredUsage, status: "PENDING" });
        } else if (recoveredUsage.status === "CANCELLED") {
          idempotencyKeyRef.current = null;
          publicRequestKeyRef.current = null;
          setFormNotice("이전 요청은 취소되었습니다.");
          setRecentPendingCapability(null);
        } else {
          setSelectedMealContractId("");
          setPartnerSearch("");
          setCustomerNameInput("");
          setAmountInput("");
          setFormNotice("이전 요청은 매장에서 처리되었습니다. 새 요청이 필요하면 다시 입력해 주세요.");
          setRecentPendingCapability(null);
          clearRequestRecovery();
        }
      } catch (error) {
        if (!active) return;
        if (error instanceof PublicQrApiError && error.status === 404) {
          setSelectedMealContractId("");
          setPartnerSearch("");
          setCustomerNameInput("");
          setAmountInput("");
          setFormNotice("이전 요청을 더 이상 확인할 수 없습니다. 새 요청을 입력해 주세요.");
          setRecentPendingCapability(null);
          clearRequestRecovery();
        } else {
          setFormError("이전 요청 상태를 확인하지 못했습니다. 네트워크를 확인한 뒤 QR을 다시 열어 주세요.");
        }
      } finally {
        if (active) {
          setIsRecoveringRequest(false);
          setRecoveryHydrated(true);
        }
      }
    }).catch(() => {
      if (!active) return;
      setRecoveryHydrated(true);
    });
    return () => {
      active = false;
    };
  }, [clearRequestRecovery, token]);

  useEffect(() => {
    if (!recoveryHydrated || !requestFingerprint || submittedUsage) return;
    if (!selectedMealContractId && !partnerSearch && !customerNameInput && !amountInput && !recentPendingCapability) {
      clearRequestRecovery();
      return;
    }
    saveRequestRecovery();
  }, [
    amountInput,
    clearRequestRecovery,
    customerNameInput,
    partnerSearch,
    recentPendingCapability,
    recoveryHydrated,
    requestFingerprint,
    saveRequestRecovery,
    selectedMealContractId,
    submittedUsage,
  ]);

  useEffect(() => {
    if (!submittedUsage) return;

    const countdownTimer = window.setInterval(() => {
      setSecondsUntilReset((seconds) => Math.max(0, seconds - 1));
    }, 1_000);
    const resetTimer = window.setTimeout(() => {
      idempotencyKeyRef.current = null;
      publicRequestKeyRef.current = null;
      setFormError(null);
      setFormNotice(null);
      setSelectedMealContractId("");
      setPartnerSearch("");
      setCustomerNameInput("");
      setAmountInput("");
      setSecondsUntilReset(SUCCESS_RESET_DELAY_SECONDS);
      setSubmittedUsage(null);
    }, SUCCESS_RESET_DELAY_MILLIS);

    return () => {
      window.clearInterval(countdownTimer);
      window.clearTimeout(resetTimer);
    };
  }, [clearRequestRecovery, submittedUsage]);

  const resetRequestKeys = () => {
    idempotencyKeyRef.current = null;
    publicRequestKeyRef.current = null;
    setFormError(null);
    setFormNotice(null);
  };

  const normalizedSearch = normalizedPartnerSearch(partnerSearch);
  const matchingPartners = !context || Array.from(normalizedSearch).length < 2
    ? []
    : context.partners.filter((partner) => normalizedPartnerSearch(partner.partnerDisplayName).includes(normalizedSearch));
  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!context || isSubmitting) return;
    if (!selectedMealContractId) {
      setFormError("협력사를 선택해 주세요.");
      return;
    }
    const normalizedCustomerName = customerNameInput.trim();
    const customerNameValidationError = customerNameError(normalizedCustomerName);
    if (customerNameValidationError) {
      setFormError(customerNameValidationError);
      return;
    }
    const validationError = amountError(amountInput);
    if (validationError) {
      setFormError(validationError);
      return;
    }
    if (!canCreatePublicRequestKey()) {
      setFormError("이 브라우저에서는 안전한 제출 키를 만들 수 없습니다.");
      return;
    }
    const idempotencyKey = idempotencyKeyRef.current ?? globalThis.crypto.randomUUID();
    const publicRequestKey = publicRequestKeyRef.current ?? createPublicRequestKey();
    if (!publicRequestKey) {
      setFormError("이 브라우저에서는 안전한 제출 키를 만들 수 없습니다.");
      return;
    }
    idempotencyKeyRef.current = idempotencyKey;
    publicRequestKeyRef.current = publicRequestKey;
    saveRequestRecovery();
    setIsSubmitting(true);
    setFormError(null);
    setFormNotice(null);
    try {
      const publicClientKey = publicClientKeyRef.current;
      if (!publicClientKey) {
        setFormError("이 브라우저에서 안전한 제출 키를 준비하지 못했습니다. 다시 시도해 주세요.");
        return;
      }
      const usage = await createPublicMealUsage(
        token, idempotencyKey, publicRequestKey, selectedMealContractId, normalizedCustomerName, Number(amountInput),
        publicClientKey
      );
      const pendingCapability = { mealUsageId: usage.mealUsageId, idempotencyKey, publicRequestKey };
      setRecentPendingCapability(pendingCapability);
      saveRequestRecovery(pendingCapability);
      setSecondsUntilReset(SUCCESS_RESET_DELAY_SECONDS);
      setSubmittedUsage(usage);
    } catch (error) {
      setFormError(submissionError(error));
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleCancel = async () => {
    if (!recentPendingCapability || isCancelling) return;
    if (!recentPendingCapability.idempotencyKey || !recentPendingCapability.publicRequestKey) {
      setFormError("이 요청을 취소할 수 없습니다. QR을 다시 열어 새 요청을 입력해 주세요.");
      return;
    }
    setIsCancelling(true);
    try {
      const cancelled = await cancelPublicMealUsage(
        token,
        recentPendingCapability.mealUsageId,
        recentPendingCapability.idempotencyKey,
        recentPendingCapability.publicRequestKey
      );
      if (cancelled.status !== "CANCELLED") {
        throw new Error("Unexpected public cancellation response");
      }
      idempotencyKeyRef.current = null;
      publicRequestKeyRef.current = null;
      clearRequestRecovery();
      setRecentPendingCapability(null);
      setSecondsUntilReset(SUCCESS_RESET_DELAY_SECONDS);
      setSubmittedUsage(null);
      setFormError(null);
      setFormNotice("요청을 취소했습니다.");
    } catch (error) {
      setFormError(error instanceof PublicQrApiError && error.status === 404
        ? "이 요청은 이미 처리되었거나 취소할 수 없습니다. 매장에 확인해 주세요."
        : "요청 취소 결과를 확인하지 못했습니다. 네트워크를 확인한 뒤 다시 시도해 주세요.");
    } finally {
      setIsCancelling(false);
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
  if (isRecoveringRequest) {
    return <StatePanel title="이전 요청을 확인하는 중" description="잠시만 기다려 주세요." busy />;
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
            <p className={mealUsageQrFormStyles.successResetNotice}>{secondsUntilReset}초 뒤 새 요청을 입력할 수 있는 화면으로 돌아갑니다.</p>
            {formError ? <p className={mealUsageQrFormStyles.error} role="alert">{formError}</p> : null}
            <button aria-busy={isCancelling} className={mealUsageQrFormStyles.cancel} disabled={isCancelling} onClick={() => void handleCancel()} type="button">
              요청 취소
            </button>
          </div>
        </section>
      </main>
    );
  }

  return (
    <main className={mealUsageQrFormStyles.page}>
      <section className={mealUsageQrFormStyles.container} aria-labelledby="qr-meal-usage-title">
        <p className={mealUsageQrFormStyles.eyebrow}>TIEAT QR</p>
        <h1 id="qr-meal-usage-title" className={mealUsageQrFormStyles.title}>{context.storeDisplayName} 식대 요청</h1>
        <p className={mealUsageQrFormStyles.description}>협력사, 고객 이름과 금액을 입력해 주세요.</p>
        {context.acceptingNewRequests === false ? (
          <div className={mealUsageQrFormStyles.state}>
            <h2 className={mealUsageQrFormStyles.stateTitle}>새 요청이 일시 중지되었습니다</h2>
            <p className={mealUsageQrFormStyles.stateDescription}>기존에 보낸 대기 요청은 이 QR에서 계속 확인하거나 취소할 수 있습니다.</p>
          </div>
        ) : context.partners.length === 0 ? (
          <div className={mealUsageQrFormStyles.state}>
            <h2 className={mealUsageQrFormStyles.stateTitle}>선택 가능한 협력사가 없습니다</h2>
            <p className={mealUsageQrFormStyles.stateDescription}>매장 직원에게 현재 식대 계약을 확인해 주세요.</p>
          </div>
        ) : (
          <form className={mealUsageQrFormStyles.card} onSubmit={(event) => void handleSubmit(event)}>
            <label className={mealUsageQrFormStyles.label} htmlFor="partner-search">협력사 검색</label>
            <input
              autoComplete="off"
              className={mealUsageQrFormStyles.input}
              disabled={isSubmitting}
              id="partner-search"
              onChange={(event) => {
                setPartnerSearch(event.target.value);
                setSelectedMealContractId("");
                resetRequestKeys();
              }}
              placeholder="협력사 이름 입력"
              type="search"
              value={partnerSearch}
            />
            {Array.from(normalizedSearch).length < 2 ? (
              <p className={mealUsageQrFormStyles.hint}>공백을 제외한 두 글자 이상을 입력해주세요.</p>
            ) : matchingPartners.length === 0 ? (
              <p className={mealUsageQrFormStyles.hint}>일치하는 협력사가 없습니다. 입력한 글자를 확인해 주세요.</p>
            ) : (
              <ul className={mealUsageQrFormStyles.partnerResults} aria-label="협력사 검색 결과">
                {matchingPartners.map((partner) => {
                  const isSelected = partner.mealContractId === selectedMealContractId;
                  return (
                    <li key={partner.mealContractId}>
                      <button
                        aria-pressed={isSelected}
                        className={isSelected ? mealUsageQrFormStyles.partnerResultSelected : mealUsageQrFormStyles.partnerResult}
                        disabled={isSubmitting}
                        onClick={() => {
                          setSelectedMealContractId(partner.mealContractId);
                          setPartnerSearch(partner.partnerDisplayName);
                          resetRequestKeys();
                        }}
                        type="button"
                      >
                        {partner.partnerDisplayName}
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
            <label className={mealUsageQrFormStyles.label} htmlFor="customer-name">고객 이름</label>
            <input
              autoComplete="off"
              className={mealUsageQrFormStyles.input}
              disabled={isSubmitting}
              id="customer-name"
              maxLength={MAX_CUSTOMER_NAME_LENGTH}
              onChange={(event) => {
                setCustomerNameInput(event.target.value);
                resetRequestKeys();
              }}
              placeholder="예: 홍길동"
              type="text"
              value={customerNameInput}
            />
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
                resetRequestKeys();
              }}
              pattern="[0-9]*"
              placeholder="예: 8500"
              type="text"
              value={amountInput}
            />
            {formNotice ? <p className={mealUsageQrFormStyles.notice} role="status">{formNotice}</p> : null}
            {formError ? <p className={mealUsageQrFormStyles.error} role="alert">{formError}</p> : null}
            <button className={mealUsageQrFormStyles.submit} disabled={isSubmitting} type="submit">
              {isSubmitting ? "요청 보내는 중…" : "요청 보내기"}
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
