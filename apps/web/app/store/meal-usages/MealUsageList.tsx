"use client";

import { FormEvent, useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import {
  ApiError,
  confirmMealUsage,
  getPendingMealUsages,
  type PendingMealUsage,
  type PendingMealUsagePage,
  UnexpectedConfirmationResponseError,
} from "@/lib/store-api";
import { mealUsageListStyles } from "./MealUsageList.styles";

type ViewState = "loading" | "ready" | "empty" | "forbidden" | "error";
type ReconciliationMode = "terminal" | "ambiguous";
type SuccessfulConfirmation = Pick<PendingMealUsage, "mealUsageId" | "amountMinor" | "entrySource">;

const amountFormatter = new Intl.NumberFormat("ko-KR", {
  style: "currency",
  currency: "KRW",
  maximumFractionDigits: 0,
});

const dateFormatter = new Intl.DateTimeFormat("ko-KR", {
  timeZone: "Asia/Seoul",
  dateStyle: "medium",
  timeStyle: "short",
});

function entrySourceLabel(source: PendingMealUsage["entrySource"]): string {
  return source === "STORE_TABLET" ? "매장 태블릿 입력" : "모바일 QR 입력";
}

function SourceIcon({ source }: { source: PendingMealUsage["entrySource"] }) {
  if (source === "STORE_TABLET") {
    return (
      <svg
        aria-hidden="true"
        className={mealUsageListStyles.sourceIconTablet}
        data-entry-source-icon="STORE_TABLET"
        fill="none"
        height="24"
        viewBox="0 0 24 24"
        width="24"
      >
        <rect height="14" rx="2.5" width="18" x="3" y="3.5" />
        <path d="M9 20.5h6M12 17.5v3" />
        <path d="M7.5 7.5h9" />
      </svg>
    );
  }

  return (
    <svg
      aria-hidden="true"
      className={mealUsageListStyles.sourceIconPartnerMobile}
      data-entry-source-icon="PARTNER_MOBILE"
      fill="none"
      height="24"
      viewBox="0 0 24 24"
      width="24"
    >
      <rect height="19" rx="2.5" width="11" x="6.5" y="2.5" />
      <path d="M9 8V6.5h1.5M15 8V6.5h-1.5M9 13v1.5h1.5M15 13v1.5h-1.5" />
      <path d="M11 10h2v2h-2zM11 18h2" />
    </svg>
  );
}

function statusLabel(): string {
  return "확인 대기";
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError && error.errorCode === "INTERNAL_SERVER_ERROR") {
    return "목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
  return "목록을 불러오지 못했습니다. 다시 시도해 주세요.";
}

function confirmationErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 400) {
    return "이니셜을 확인해 주세요.";
  }
  if (error instanceof ApiError && error.status === 403 && error.errorCode === "CSRF_TOKEN_INVALID") {
    return "보안 확인이 만료되었습니다. 이니셜은 유지되며 다시 확정할 수 있습니다.";
  }
  return "확정 결과를 바로 알 수 없습니다. 목록을 다시 불러와 확인해 주세요.";
}

export function MealUsageList() {
  const router = useRouter();
  const [items, setItems] = useState<PendingMealUsage[]>([]);
  const [viewState, setViewState] = useState<ViewState>("loading");
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [refreshError, setRefreshError] = useState<string | null>(null);
  const [selectedMealUsage, setSelectedMealUsage] = useState<PendingMealUsage | null>(null);
  const [initials, setInitials] = useState("");
  const [confirmationError, setConfirmationError] = useState<string | null>(null);
  const [confirmationNotice, setConfirmationNotice] = useState<string | null>(null);
  const [successfulConfirmation, setSuccessfulConfirmation] = useState<SuccessfulConfirmation | null>(null);
  const [isConfirming, setIsConfirming] = useState(false);
  const [isReconciling, setIsReconciling] = useState(false);
  const [requiresReconciliation, setRequiresReconciliation] = useState(false);
  const [reconciliationMode, setReconciliationMode] = useState<ReconciliationMode | null>(null);
  const hasItemsRef = useRef(false);
  const selectedMealUsageRef = useRef<PendingMealUsage | null>(null);
  const confirmationInFlightRef = useRef(false);
  const successfulConfirmationRef = useRef<SuccessfulConfirmation | null>(null);
  const pendingPageRequestInFlightRef = useRef(false);
  const initialsInputRef = useRef<HTMLInputElement>(null);
  const mountedRef = useRef(true);
  const requestEpochRef = useRef(0);

  const beginPendingRequest = useCallback(() => {
    requestEpochRef.current += 1;
    return requestEpochRef.current;
  }, []);

  const isCurrentPendingRequest = useCallback((epoch: number) => (
    mountedRef.current && requestEpochRef.current === epoch
  ), []);

  const invalidatePendingRequests = useCallback(() => {
    requestEpochRef.current += 1;
  }, []);

  const clearSelection = useCallback(() => {
    selectedMealUsageRef.current = null;
    setSelectedMealUsage(null);
    setInitials("");
    setConfirmationError(null);
    setRequiresReconciliation(false);
    setReconciliationMode(null);
  }, []);

  const clearSuccessfulConfirmation = useCallback(() => {
    successfulConfirmationRef.current = null;
    setSuccessfulConfirmation(null);
  }, []);

  const selectMealUsage = useCallback((item: PendingMealUsage) => {
    selectedMealUsageRef.current = item;
    setSelectedMealUsage(item);
    setInitials("");
    setConfirmationError(null);
    setConfirmationNotice(null);
    setRefreshError(null);
    setRequiresReconciliation(false);
    setReconciliationMode(null);
    clearSuccessfulConfirmation();
  }, [clearSuccessfulConfirmation]);

  const clearSensitiveRows = useCallback(() => {
    setItems([]);
    hasItemsRef.current = false;
    setRefreshError(null);
    setConfirmationNotice(null);
    clearSuccessfulConfirmation();
    clearSelection();
  }, [clearSelection, clearSuccessfulConfirmation]);

  const applyPendingPage = useCallback((result: PendingMealUsagePage) => {
    setItems(result.items);
    hasItemsRef.current = result.items.length > 0;
    setViewState(result.items.length === 0 ? "empty" : "ready");

    const selectedId = selectedMealUsageRef.current?.mealUsageId;
    if (!selectedId) return;

    const currentSelection = result.items.find((item) => item.mealUsageId === selectedId);
    if (!currentSelection) {
      clearSelection();
      return;
    }
    selectedMealUsageRef.current = currentSelection;
    setSelectedMealUsage(currentSelection);
  }, [clearSelection]);

  const redirectToLogin = useCallback(() => {
    invalidatePendingRequests();
    clearSensitiveRows();
    setViewState("loading");
    router.replace("/store/login?next=/store/meal-usages");
  }, [clearSensitiveRows, invalidatePendingRequests, router]);

  const denyAccess = useCallback(() => {
    invalidatePendingRequests();
    clearSensitiveRows();
    setViewState("forbidden");
  }, [clearSensitiveRows, invalidatePendingRequests]);

  const load = useCallback(async (isRefresh = false) => {
    if (pendingPageRequestInFlightRef.current || confirmationInFlightRef.current) return;
    pendingPageRequestInFlightRef.current = true;
    const epoch = beginPendingRequest();
    if (isRefresh) {
      setIsRefreshing(true);
      setRefreshError(null);
      setConfirmationError(null);
      setConfirmationNotice(null);
      clearSuccessfulConfirmation();
    }

    try {
      const result = await getPendingMealUsages();
      if (!isCurrentPendingRequest(epoch)) return;
      applyPendingPage(result);
    } catch (error) {
      if (!isCurrentPendingRequest(epoch)) return;
      if (error instanceof ApiError && error.status === 401) {
        redirectToLogin();
        return;
      }
      if (error instanceof ApiError && error.status === 403) {
        denyAccess();
        return;
      }
      if (isRefresh && hasItemsRef.current) {
        setRefreshError(errorMessage(error));
      } else {
        setViewState("error");
      }
    } finally {
      pendingPageRequestInFlightRef.current = false;
      if (isRefresh && isCurrentPendingRequest(epoch)) {
        setIsRefreshing(false);
      }
    }
  }, [applyPendingPage, beginPendingRequest, clearSuccessfulConfirmation, denyAccess, isCurrentPendingRequest, redirectToLogin]);

  const reconcileAfterConfirmation = useCallback(async (mode: ReconciliationMode, notice: string) => {
    if (pendingPageRequestInFlightRef.current) return;
    pendingPageRequestInFlightRef.current = true;
    const epoch = beginPendingRequest();
    setIsReconciling(true);
    setRequiresReconciliation(true);
    setReconciliationMode(mode);
    setConfirmationError(null);
    setConfirmationNotice(null);
    try {
      const result = await getPendingMealUsages();
      if (!isCurrentPendingRequest(epoch)) return;
      applyPendingPage(result);
      const confirmedUsageWasOmitted = successfulConfirmationRef.current
        && !result.items.some((item) => item.mealUsageId === successfulConfirmationRef.current?.mealUsageId);
      if (confirmedUsageWasOmitted) {
        setSuccessfulConfirmation(successfulConfirmationRef.current);
        setConfirmationError(null);
        setConfirmationNotice(null);
        setRefreshError(null);
      }
      const selectedStillPending = selectedMealUsageRef.current !== null;
      if (mode === "terminal" && selectedStillPending) {
        setRequiresReconciliation(true);
        setReconciliationMode("terminal");
        setConfirmationError("확정 상태를 다시 확인해야 합니다. 목록 다시 불러오기로만 확인할 수 있습니다.");
        return;
      }
      setRequiresReconciliation(false);
      setReconciliationMode(null);
      setConfirmationError(null);
      if (confirmedUsageWasOmitted) {
        setConfirmationNotice(null);
      } else {
        setConfirmationNotice(selectedStillPending ? notice : "목록을 최신 상태로 반영했습니다.");
      }
    } catch (error) {
      if (!isCurrentPendingRequest(epoch)) return;
      if (error instanceof ApiError && error.status === 401) {
        redirectToLogin();
        return;
      }
      if (error instanceof ApiError && error.status === 403) {
        denyAccess();
        return;
      }
      setConfirmationNotice(null);
      setConfirmationError("확정 결과를 확인하지 못했습니다. 목록을 다시 불러와 확인해 주세요.");
    } finally {
      pendingPageRequestInFlightRef.current = false;
      if (isCurrentPendingRequest(epoch)) {
        setIsReconciling(false);
      }
    }
  }, [applyPendingPage, beginPendingRequest, denyAccess, isCurrentPendingRequest, redirectToLogin]);

  const handleConfirmation = useCallback(async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const currentSelection = selectedMealUsageRef.current;
    if (!currentSelection
      || confirmationInFlightRef.current
      || pendingPageRequestInFlightRef.current
      || requiresReconciliation) return;

    if (initials.trim().length === 0) {
      setConfirmationError("이니셜을 입력해 주세요.");
      window.setTimeout(() => initialsInputRef.current?.focus(), 0);
      return;
    }

    confirmationInFlightRef.current = true;
    setIsConfirming(true);
    setConfirmationError(null);
    setConfirmationNotice(null);
    setRefreshError(null);

    try {
      await confirmMealUsage(currentSelection.mealUsageId, initials);
      if (!mountedRef.current) return;
      successfulConfirmationRef.current = {
        mealUsageId: currentSelection.mealUsageId,
        amountMinor: currentSelection.amountMinor,
        entrySource: currentSelection.entrySource,
      };
      setSuccessfulConfirmation(null);
      setConfirmationError(null);
      setConfirmationNotice(null);
      await reconcileAfterConfirmation("terminal", "확정을 반영해 목록을 다시 불러왔습니다.");
    } catch (error) {
      if (!mountedRef.current) return;
      if (error instanceof UnexpectedConfirmationResponseError) {
        await reconcileAfterConfirmation("terminal", "확정 응답을 목록으로 다시 확인했습니다.");
        return;
      }
      if (error instanceof ApiError && error.status === 401) {
        redirectToLogin();
        return;
      }
      if (error instanceof ApiError && error.status === 403) {
        if (error.errorCode === "CSRF_TOKEN_INVALID") {
          setConfirmationError(confirmationErrorMessage(error));
        } else {
          denyAccess();
        }
        return;
      }
      if (error instanceof ApiError && error.status === 400) {
        setConfirmationError(confirmationErrorMessage(error));
        window.setTimeout(() => initialsInputRef.current?.focus(), 0);
        return;
      }
      if (error instanceof ApiError
        && (error.status === 404
          || (error.status === 409 && (error.errorCode === "MEAL_USAGE_ALREADY_CONFIRMED" || error.errorCode === "MEAL_USAGE_CONFIRMATION_CONFLICT")))) {
        await reconcileAfterConfirmation("terminal", "이미 처리된 거래인지 목록으로 다시 확인했습니다.");
        return;
      }
      if (error instanceof ApiError && error.status > 0 && error.status < 500) {
        await reconcileAfterConfirmation("terminal", "확정 요청 상태를 목록으로 다시 확인했습니다.");
        return;
      }
      await reconcileAfterConfirmation("ambiguous", "확정 결과를 목록으로 확인했습니다. 거래가 아직 확인 대기이면 다시 확정할 수 있습니다.");
    } finally {
      confirmationInFlightRef.current = false;
      if (mountedRef.current) {
        setIsConfirming(false);
      }
    }
  }, [denyAccess, initials, reconcileAfterConfirmation, redirectToLogin, requiresReconciliation]);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      invalidatePendingRequests();
    };
  }, [invalidatePendingRequests]);

  useEffect(() => {
    let active = true;
    pendingPageRequestInFlightRef.current = true;
    const epoch = beginPendingRequest();
    void getPendingMealUsages()
      .then((result) => {
        if (active && isCurrentPendingRequest(epoch)) applyPendingPage(result);
      })
      .catch((error: unknown) => {
        if (!active || !isCurrentPendingRequest(epoch)) return;
        if (error instanceof ApiError && error.status === 401) {
          redirectToLogin();
          return;
        }
        if (error instanceof ApiError && error.status === 403) {
          denyAccess();
          return;
        }
        setViewState("error");
      })
      .finally(() => {
        pendingPageRequestInFlightRef.current = false;
      });
    return () => {
      active = false;
    };
  }, [applyPendingPage, beginPendingRequest, denyAccess, isCurrentPendingRequest, redirectToLogin]);

  if (viewState === "loading") {
    return (
      <main className={mealUsageListStyles.page} aria-busy="true">
        <section className={mealUsageListStyles.container} aria-label="확인 대기 거래 불러오는 중">
          <div className="h-4 w-24 rounded-sm bg-[var(--surface)]" />
          <div className="mt-3 h-9 w-52 rounded-sm bg-[var(--surface)]" />
          <div className={`${mealUsageListStyles.card} p-5`}>
            {[0, 1, 2].map((item) => <div className={`${mealUsageListStyles.skeleton} mb-3 h-16 rounded-lg last:mb-0`} key={item} />)}
          </div>
        </section>
      </main>
    );
  }

  if (viewState === "forbidden") {
    return <StatePanel title="접근 권한이 없습니다" description="이 계정으로는 확인 대기 거래를 볼 수 없습니다." />;
  }

  if (viewState === "error") {
    return <StatePanel title="목록을 불러오지 못했습니다" description="네트워크 상태를 확인한 뒤 다시 시도해 주세요." onRetry={() => void load()} />;
  }

  const rowActionDisabled = isConfirming || isReconciling || isRefreshing || requiresReconciliation;

  return (
    <main className={mealUsageListStyles.page}>
      <section className={mealUsageListStyles.container} aria-labelledby="pending-title">
        <header className={mealUsageListStyles.header}>
          <div>
            <p className={mealUsageListStyles.eyebrow}>TIEAT STORE</p>
            <h1 id="pending-title" className={mealUsageListStyles.title}>확인 대기 거래</h1>
            <p className={mealUsageListStyles.description}>오래된 거래부터 표시합니다.</p>
          </div>
          <button className={mealUsageListStyles.refresh} type="button" onClick={() => void load(true)} disabled={isRefreshing || isReconciling || isConfirming}>
            {isRefreshing ? "새로고침 중…" : "새로고침"}
          </button>
        </header>

        <div className={mealUsageListStyles.card}>
          {refreshError ? <p className={mealUsageListStyles.notice} role="alert">{refreshError}</p> : null}
          {confirmationNotice ? <p className={mealUsageListStyles.statusNotice} role="status">{confirmationNotice}</p> : null}
          {successfulConfirmation ? (
            <div className={mealUsageListStyles.successNotice} role="status">
              <svg aria-hidden="true" className={mealUsageListStyles.successIcon} fill="none" height="20" viewBox="0 0 24 24" width="20">
                <circle cx="12" cy="12" r="8.5" />
                <path d="m8.5 12 2.3 2.3 4.8-5" />
              </svg>
              <div className={mealUsageListStyles.successCopy}>
                <p className={mealUsageListStyles.successTitle}>{amountFormatter.format(successfulConfirmation.amountMinor)} 거래 확정 완료</p>
                <p className={mealUsageListStyles.successDescription}>{entrySourceLabel(successfulConfirmation.entrySource)}</p>
              </div>
            </div>
          ) : null}
          {viewState === "empty" ? (
            <div className={mealUsageListStyles.state}>
              <h2 className={mealUsageListStyles.stateTitle}>확인 대기 거래가 없습니다</h2>
              <p className={mealUsageListStyles.stateDescription}>새 거래가 생기면 이 목록에서 확인할 수 있습니다.</p>
            </div>
          ) : (
            <>
              <ul className={mealUsageListStyles.list} aria-label="확인 대기 거래 목록">
                {items.map((item) => {
                  const isSelected = item.mealUsageId === selectedMealUsage?.mealUsageId;
                  return (
                    <li key={item.mealUsageId}>
                      <button
                        aria-controls={isSelected ? "confirmation-panel" : undefined}
                        aria-expanded={isSelected}
                        className={`${mealUsageListStyles.row} ${isSelected ? mealUsageListStyles.selectedRow : ""}`}
                        disabled={rowActionDisabled}
                        onClick={() => selectMealUsage(item)}
                        type="button"
                      >
                        <span className={mealUsageListStyles.metadata}>
                          <span className={mealUsageListStyles.source}>
                            <SourceIcon source={item.entrySource} />
                            <span>{entrySourceLabel(item.entrySource)}</span>
                          </span>
                          <time className={mealUsageListStyles.createdAt} dateTime={item.createdAt}>{dateFormatter.format(new Date(item.createdAt))}</time>
                        </span>
                        <strong className={mealUsageListStyles.amount}>{amountFormatter.format(item.amountMinor)}</strong>
                        <span className={mealUsageListStyles.status}>{statusLabel()}</span>
                      </button>
                    </li>
                  );
                })}
              </ul>
              {selectedMealUsage ? (
                <section id="confirmation-panel" className={mealUsageListStyles.confirmationPanel} aria-labelledby="confirmation-title">
                  <div>
                    <p className={mealUsageListStyles.confirmationEyebrow}>선택한 거래</p>
                    <h2 id="confirmation-title" className={mealUsageListStyles.confirmationTitle}>이 거래를 확정할까요?</h2>
                  </div>
                  <dl className={mealUsageListStyles.confirmationSummary}>
                    <div>
                      <dt>입력 경로</dt>
                      <dd>{entrySourceLabel(selectedMealUsage.entrySource)}</dd>
                    </div>
                    <div>
                      <dt>입력 시각</dt>
                      <dd><time dateTime={selectedMealUsage.createdAt}>{dateFormatter.format(new Date(selectedMealUsage.createdAt))}</time></dd>
                    </div>
                    <div>
                      <dt>금액</dt>
                      <dd className={mealUsageListStyles.confirmationAmount}>{amountFormatter.format(selectedMealUsage.amountMinor)}</dd>
                    </div>
                    <div>
                      <dt>상태</dt>
                      <dd>{statusLabel()}</dd>
                    </div>
                  </dl>
                  <form className={mealUsageListStyles.confirmationForm} onSubmit={handleConfirmation}>
                    <label className={mealUsageListStyles.initialsLabel} htmlFor="confirmer-initials">확인자 이니셜</label>
                    <input
                      aria-describedby={confirmationError ? "confirmation-error" : undefined}
                      aria-invalid={Boolean(confirmationError)}
                      className={mealUsageListStyles.initialsInput}
                      disabled={isConfirming || isReconciling}
                      id="confirmer-initials"
                      onChange={(event) => setInitials(event.target.value)}
                      ref={initialsInputRef}
                      value={initials}
                    />
                    <p className={mealUsageListStyles.initialsHint}>확정 기록에 입력한 그대로 남습니다.</p>
                    {confirmationError ? <p id="confirmation-error" className={mealUsageListStyles.confirmationError} role="alert">{confirmationError}</p> : null}
                    {requiresReconciliation ? (
                      <button className={mealUsageListStyles.reconcile} type="button" disabled={isReconciling || isRefreshing} onClick={() => void reconcileAfterConfirmation(reconciliationMode ?? "ambiguous", "목록을 최신 상태로 반영했습니다.")}>
                        {isReconciling ? "목록 확인 중…" : "목록 다시 불러오기"}
                      </button>
                    ) : (
                      <button className={mealUsageListStyles.confirm} disabled={isConfirming || isReconciling || isRefreshing} type="submit">
                        {isConfirming || isReconciling ? "확정 처리 중…" : "이니셜로 확정"}
                      </button>
                    )}
                  </form>
                </section>
              ) : null}
            </>
          )}
        </div>
      </section>
    </main>
  );
}

function StatePanel({ title, description, onRetry }: { title: string; description: string; onRetry?: () => void }) {
  return (
    <main className={mealUsageListStyles.page}>
      <section className={`${mealUsageListStyles.container} ${mealUsageListStyles.card} ${mealUsageListStyles.state}`} aria-live="polite">
        <h1 className={mealUsageListStyles.stateTitle}>{title}</h1>
        <p className={mealUsageListStyles.stateDescription}>{description}</p>
        {onRetry ? <button className={mealUsageListStyles.stateAction} type="button" onClick={onRetry}>다시 시도</button> : null}
      </section>
    </main>
  );
}
