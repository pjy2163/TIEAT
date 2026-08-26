"use client";

import { FormEvent, useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import {
  ApiError,
  confirmMealUsage,
  getPendingMealUsages,
  rejectMealUsage,
  type PendingMealUsage,
  type PendingMealUsagePage,
  UnexpectedConfirmationResponseError,
  UnexpectedRejectionResponseError,
} from "@/lib/store-api";
import { mealUsageListStyles } from "./MealUsageList.styles";
import { RefreshButton } from "./RefreshButton";

type ViewState = "loading" | "ready" | "empty" | "forbidden" | "error";
type ReconciliationMode = "terminal" | "ambiguous";
type LoadReason = "initial" | "manual" | "automatic";
type SuccessfulConfirmation = Pick<PendingMealUsage, "mealUsageId">;
type PendingPageApplication = "no-selection" | "selection-cleared" | "terminal-pending" | "ambiguous-pending" | "pending";
type DeferredPendingPageChange = "new-request" | "changed";

const POLLING_INTERVAL_MILLIS = 2_000;
const MAX_POLLING_RETRY_MILLIS = 30_000;
const TERMINAL_SUCCESS_NOTICE_DISMISS_MILLIS = 5_000;
const REJECTION_SUCCESS_NOTICE = "요청을 거절했습니다.";

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

function isSamePendingPage(current: PendingMealUsage[], next: PendingMealUsage[]): boolean {
  return current.length === next.length && current.every((item, index) => {
    const nextItem = next[index];
    return item.mealUsageId === nextItem.mealUsageId
      && item.status === nextItem.status
      && item.entrySource === nextItem.entrySource
      && item.partnerDisplayName === nextItem.partnerDisplayName
      && item.customerName === nextItem.customerName
      && item.amountMinor === nextItem.amountMinor
      && item.createdAt === nextItem.createdAt;
  });
}

export function MealUsageList() {
  const router = useRouter();
  const [items, setItems] = useState<PendingMealUsage[]>([]);
  const [viewState, setViewState] = useState<ViewState>("loading");
  const [isPendingPageLoading, setIsPendingPageLoading] = useState(false);
  const [isAutomaticPolling, setIsAutomaticPolling] = useState(false);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [refreshError, setRefreshError] = useState<string | null>(null);
  const [deferredPendingPageChange, setDeferredPendingPageChange] = useState<DeferredPendingPageChange | null>(null);
  const [selectedMealUsage, setSelectedMealUsage] = useState<PendingMealUsage | null>(null);
  const [initials, setInitials] = useState("");
  const [confirmationError, setConfirmationError] = useState<string | null>(null);
  const [confirmationNotice, setConfirmationNotice] = useState<string | null>(null);
  const [successfulConfirmation, setSuccessfulConfirmation] = useState<SuccessfulConfirmation | null>(null);
  const [isConfirming, setIsConfirming] = useState(false);
  const [isRejecting, setIsRejecting] = useState(false);
  const [isReconciling, setIsReconciling] = useState(false);
  const [requiresReconciliation, setRequiresReconciliation] = useState(false);
  const [reconciliationMode, setReconciliationMode] = useState<ReconciliationMode | null>(null);
  const hasItemsRef = useRef(false);
  const hasAuthoritativePendingPageRef = useRef(false);
  const displayedPendingPageRef = useRef<PendingMealUsage[]>([]);
  const selectedMealUsageRef = useRef<PendingMealUsage | null>(null);
  const confirmationInFlightRef = useRef(false);
  const successfulConfirmationRef = useRef<SuccessfulConfirmation | null>(null);
  const reconciliationModeRef = useRef<ReconciliationMode | null>(null);
  const pendingPageRequestInFlightRef = useRef(false);
  const realtimeRefreshDirtyRef = useRef(false);
  const realtimeRefreshReasonRef = useRef<LoadReason>("automatic");
  const flushRealtimeRefreshRef = useRef<() => void>(() => undefined);
  const requestRealtimeRefreshRef = useRef<() => void>(() => undefined);
  const pollTimerRef = useRef<number | null>(null);
  const visibleRef = useRef(false);
  const pollingStoppedRef = useRef(false);
  const pollingFailureCountRef = useRef(0);
  const nextPollingDelayRef = useRef(POLLING_INTERVAL_MILLIS);
  const settledRequestCanScheduleRef = useRef(false);
  const settledRequestFailedRef = useRef(false);
  const confirmationNeedsPollingResumeRef = useRef(false);
  const initialsInputRef = useRef<HTMLInputElement>(null);
  const mountedRef = useRef(false);
  const requestEpochRef = useRef(0);

  const clearScheduledPolling = useCallback(() => {
    if (pollTimerRef.current === null) return;
    window.clearTimeout(pollTimerRef.current);
    pollTimerRef.current = null;
  }, []);

  const canPoll = useCallback(() => (
    mountedRef.current && visibleRef.current && !pollingStoppedRef.current
  ), []);

  const beginPendingRequest = useCallback(() => {
    requestEpochRef.current += 1;
    return requestEpochRef.current;
  }, []);

  const isCurrentPendingRequest = useCallback((epoch: number) => (
    canPoll() && requestEpochRef.current === epoch
  ), [canPoll]);

  const invalidatePendingRequests = useCallback(() => {
    requestEpochRef.current += 1;
  }, []);

  const stopPolling = useCallback(() => {
    pollingStoppedRef.current = true;
    realtimeRefreshDirtyRef.current = false;
    settledRequestCanScheduleRef.current = false;
    clearScheduledPolling();
  }, [clearScheduledPolling]);

  const clearSelection = useCallback(() => {
    selectedMealUsageRef.current = null;
    setSelectedMealUsage(null);
    setInitials("");
    setConfirmationError(null);
    setRequiresReconciliation(false);
    setReconciliationMode(null);
    reconciliationModeRef.current = null;
  }, []);

  const clearSuccessfulConfirmation = useCallback(() => {
    successfulConfirmationRef.current = null;
    setSuccessfulConfirmation(null);
  }, []);

  useEffect(() => {
    if (!successfulConfirmation) return;
    const timer = window.setTimeout(clearSuccessfulConfirmation, TERMINAL_SUCCESS_NOTICE_DISMISS_MILLIS);
    return () => window.clearTimeout(timer);
  }, [clearSuccessfulConfirmation, successfulConfirmation]);

  useEffect(() => {
    if (confirmationNotice !== REJECTION_SUCCESS_NOTICE) return;
    const timer = window.setTimeout(() => setConfirmationNotice(null), TERMINAL_SUCCESS_NOTICE_DISMISS_MILLIS);
    return () => window.clearTimeout(timer);
  }, [confirmationNotice]);

  const selectMealUsage = useCallback((item: PendingMealUsage) => {
    selectedMealUsageRef.current = item;
    setSelectedMealUsage(item);
    setInitials("");
    setConfirmationError(null);
    setConfirmationNotice(null);
    setRefreshError(null);
    setRequiresReconciliation(false);
    setReconciliationMode(null);
    reconciliationModeRef.current = null;
    clearSuccessfulConfirmation();
  }, [clearSuccessfulConfirmation]);

  const clearSensitiveRows = useCallback(() => {
    setItems([]);
    hasItemsRef.current = false;
    displayedPendingPageRef.current = [];
    setDeferredPendingPageChange(null);
    setRefreshError(null);
    setConfirmationNotice(null);
    clearSuccessfulConfirmation();
    clearSelection();
  }, [clearSelection, clearSuccessfulConfirmation]);

  const applyPendingPage = useCallback((result: PendingMealUsagePage): PendingPageApplication => {
    hasAuthoritativePendingPageRef.current = true;
    displayedPendingPageRef.current = result.items;
    setItems(result.items);
    hasItemsRef.current = result.items.length > 0;
    setViewState(result.items.length === 0 ? "empty" : "ready");
    setRefreshError(null);
    setDeferredPendingPageChange(null);

    const selectedId = selectedMealUsageRef.current?.mealUsageId;
    const successfulConfirmationWasOmitted = successfulConfirmationRef.current
      && !result.items.some((item) => item.mealUsageId === successfulConfirmationRef.current?.mealUsageId);
    if (successfulConfirmationWasOmitted) {
      setSuccessfulConfirmation(successfulConfirmationRef.current);
      setRefreshError(null);
      setConfirmationError(null);
      setConfirmationNotice(null);
    }

    if (!selectedId) return "no-selection";

    const currentSelection = result.items.find((item) => item.mealUsageId === selectedId);
    if (!currentSelection) {
      clearSelection();
      return "selection-cleared";
    }
    selectedMealUsageRef.current = currentSelection;
    setSelectedMealUsage(currentSelection);
    if (reconciliationModeRef.current === "terminal") {
      setRequiresReconciliation(true);
      setConfirmationNotice(null);
      setConfirmationError("확정 상태를 다시 확인해야 합니다. 목록 다시 불러오기로만 확인할 수 있습니다.");
      return "terminal-pending";
    }
    if (reconciliationModeRef.current === "ambiguous") {
      reconciliationModeRef.current = null;
      setReconciliationMode(null);
      setRequiresReconciliation(false);
      setConfirmationError(null);
      return "ambiguous-pending";
    }
    return "pending";
  }, [clearSelection]);

  const redirectToLogin = useCallback(() => {
    stopPolling();
    invalidatePendingRequests();
    clearSensitiveRows();
    setViewState("loading");
    router.replace("/store/login?next=/store/meal-usages");
  }, [clearSensitiveRows, invalidatePendingRequests, router, stopPolling]);

  const denyAccess = useCallback(() => {
    stopPolling();
    invalidatePendingRequests();
    clearSensitiveRows();
    setViewState("forbidden");
  }, [clearSensitiveRows, invalidatePendingRequests, stopPolling]);

  const scheduleNextPolling = useCallback((delay: number) => {
    clearScheduledPolling();
    if (!canPoll()) return;
    pollTimerRef.current = window.setTimeout(() => {
      pollTimerRef.current = null;
      requestRealtimeRefreshRef.current();
    }, delay);
  }, [canPoll, clearScheduledPolling]);

  const load = useCallback(async (reason: LoadReason) => {
    if (!canPoll()) return;
    if (reason === "manual") {
      setIsRefreshing(true);
      setRefreshError(null);
      setConfirmationError(null);
      setConfirmationNotice(null);
      clearSuccessfulConfirmation();
    }
    if (pendingPageRequestInFlightRef.current || confirmationInFlightRef.current) {
      realtimeRefreshDirtyRef.current = true;
      if (reason === "manual") {
        realtimeRefreshReasonRef.current = "manual";
      }
      return;
    }

    clearScheduledPolling();
    settledRequestCanScheduleRef.current = false;
    pendingPageRequestInFlightRef.current = true;
    if (reason === "automatic") {
      setIsAutomaticPolling(true);
    } else {
      setIsPendingPageLoading(true);
    }
    const epoch = beginPendingRequest();
    let requestCanSchedule = false;
    let requestFailed = false;

    try {
      const result = await getPendingMealUsages();
      if (!isCurrentPendingRequest(epoch)) return;
      pollingFailureCountRef.current = 0;
      nextPollingDelayRef.current = POLLING_INTERVAL_MILLIS;
      requestCanSchedule = true;
      if (reason === "automatic" && selectedMealUsageRef.current !== null) {
        const displayedPage = displayedPendingPageRef.current;
        const displayedIds = new Set(displayedPage.map((item) => item.mealUsageId));
        const hasNewRequest = result.items.some((item) => !displayedIds.has(item.mealUsageId));
        const hasChangedPage = !isSamePendingPage(displayedPage, result.items);
        if (hasNewRequest) {
          setDeferredPendingPageChange("new-request");
        } else if (hasChangedPage) {
          setDeferredPendingPageChange("changed");
        }
      } else {
        applyPendingPage(result);
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
      const failureExponent = Math.min(pollingFailureCountRef.current, 4);
      nextPollingDelayRef.current = Math.min(
        POLLING_INTERVAL_MILLIS * (2 ** failureExponent),
        MAX_POLLING_RETRY_MILLIS,
      );
      pollingFailureCountRef.current = Math.min(failureExponent + 1, 4);
      requestCanSchedule = true;
      requestFailed = true;
      if (reason === "manual" && hasItemsRef.current) {
        setRefreshError(errorMessage(error));
      } else if (reason === "automatic" && hasAuthoritativePendingPageRef.current) {
        return;
      } else {
        setViewState("error");
      }
    } finally {
      pendingPageRequestInFlightRef.current = false;
      if (reason === "automatic" && mountedRef.current) {
        setIsAutomaticPolling(false);
      }
      if (reason !== "automatic" && mountedRef.current) {
        setIsPendingPageLoading(false);
      }
      if (reason === "manual" && mountedRef.current) {
        setIsRefreshing(false);
      }
      if (isCurrentPendingRequest(epoch)) {
        settledRequestCanScheduleRef.current = requestCanSchedule;
        settledRequestFailedRef.current = requestFailed;
      }
      flushRealtimeRefreshRef.current();
    }
  }, [applyPendingPage, beginPendingRequest, canPoll, clearScheduledPolling, clearSuccessfulConfirmation, denyAccess, isCurrentPendingRequest, redirectToLogin]);

  const requestRealtimeRefresh = useCallback(() => {
    if (!canPoll()) return;
    if (pendingPageRequestInFlightRef.current || confirmationInFlightRef.current) {
      realtimeRefreshDirtyRef.current = true;
      return;
    }
    void load("automatic");
  }, [canPoll, load]);

  const flushRealtimeRefresh = useCallback(() => {
    if (!canPoll()
      || pendingPageRequestInFlightRef.current
      || confirmationInFlightRef.current) return;

    const requestCanSchedule = settledRequestCanScheduleRef.current;
    const requestFailed = settledRequestFailedRef.current;
    settledRequestCanScheduleRef.current = false;
    settledRequestFailedRef.current = false;

    if (realtimeRefreshDirtyRef.current) {
      const reason = realtimeRefreshReasonRef.current;
      realtimeRefreshDirtyRef.current = false;
      realtimeRefreshReasonRef.current = "automatic";
      if (requestCanSchedule && requestFailed && reason !== "manual") {
        scheduleNextPolling(nextPollingDelayRef.current);
        return;
      }
      void load(reason);
      return;
    }

    if (requestCanSchedule) {
      scheduleNextPolling(nextPollingDelayRef.current);
    }
  }, [canPoll, load, scheduleNextPolling]);

  useEffect(() => {
    flushRealtimeRefreshRef.current = flushRealtimeRefresh;
    requestRealtimeRefreshRef.current = requestRealtimeRefresh;
  }, [flushRealtimeRefresh, requestRealtimeRefresh]);

  const reconcileAfterConfirmation = useCallback(async (mode: ReconciliationMode, notice: string) => {
    if (!canPoll()) return;
    if (pendingPageRequestInFlightRef.current) {
      realtimeRefreshDirtyRef.current = true;
      return;
    }
    confirmationNeedsPollingResumeRef.current = false;
    clearScheduledPolling();
    realtimeRefreshDirtyRef.current = false;
    settledRequestCanScheduleRef.current = false;
    pendingPageRequestInFlightRef.current = true;
    setIsPendingPageLoading(true);
    const epoch = beginPendingRequest();
    let requestCanSchedule = false;
    let requestFailed = false;
    setIsReconciling(true);
    setRequiresReconciliation(true);
    setReconciliationMode(mode);
    reconciliationModeRef.current = mode;
    setConfirmationError(null);
    setConfirmationNotice(null);
    try {
      const result = await getPendingMealUsages();
      if (!isCurrentPendingRequest(epoch)) return;
      pollingFailureCountRef.current = 0;
      nextPollingDelayRef.current = POLLING_INTERVAL_MILLIS;
      requestCanSchedule = true;
      const applied = applyPendingPage(result);
      if (applied === "terminal-pending") return;

      if (applied === "selection-cleared" && successfulConfirmationRef.current !== null) {
        setConfirmationNotice(null);
      } else {
        setConfirmationNotice(notice);
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
      const failureExponent = Math.min(pollingFailureCountRef.current, 4);
      nextPollingDelayRef.current = Math.min(
        POLLING_INTERVAL_MILLIS * (2 ** failureExponent),
        MAX_POLLING_RETRY_MILLIS,
      );
      pollingFailureCountRef.current = Math.min(failureExponent + 1, 4);
      requestCanSchedule = true;
      requestFailed = true;
      setConfirmationNotice(null);
      setConfirmationError("확정 결과를 확인하지 못했습니다. 목록을 다시 불러와 확인해 주세요.");
    } finally {
      pendingPageRequestInFlightRef.current = false;
      if (mountedRef.current) {
        setIsPendingPageLoading(false);
        setIsReconciling(false);
      }
      if (isCurrentPendingRequest(epoch)) {
        settledRequestCanScheduleRef.current = requestCanSchedule;
        settledRequestFailedRef.current = requestFailed;
      }
      flushRealtimeRefreshRef.current();
    }
  }, [applyPendingPage, beginPendingRequest, canPoll, clearScheduledPolling, denyAccess, isCurrentPendingRequest, redirectToLogin]);

  const handleManualRefresh = useCallback(() => {
    if (requiresReconciliation) {
      void reconcileAfterConfirmation(reconciliationMode ?? "ambiguous", "목록을 최신 상태로 반영했습니다.");
      return;
    }
    void load("manual");
  }, [load, reconciliationMode, reconcileAfterConfirmation, requiresReconciliation]);

  const handleConfirmation = useCallback(async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const currentSelection = selectedMealUsageRef.current;
    if (!currentSelection
      || !canPoll()
      || confirmationInFlightRef.current
      || isRejecting
      || pendingPageRequestInFlightRef.current
      || requiresReconciliation) return;

    if (initials.trim().length === 0) {
      setConfirmationError("이니셜을 입력해 주세요.");
      window.setTimeout(() => initialsInputRef.current?.focus(), 0);
      return;
    }

    confirmationInFlightRef.current = true;
    confirmationNeedsPollingResumeRef.current = true;
    clearScheduledPolling();
    setIsConfirming(true);
    setConfirmationError(null);
    setConfirmationNotice(null);
    setRefreshError(null);

    try {
      await confirmMealUsage(currentSelection.mealUsageId, initials);
      if (!mountedRef.current) return;
      successfulConfirmationRef.current = {
        mealUsageId: currentSelection.mealUsageId,
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
      if (confirmationNeedsPollingResumeRef.current && canPoll()) {
        settledRequestCanScheduleRef.current = true;
        settledRequestFailedRef.current = false;
        nextPollingDelayRef.current = POLLING_INTERVAL_MILLIS;
      }
      confirmationNeedsPollingResumeRef.current = false;
      flushRealtimeRefreshRef.current();
    }
  }, [canPoll, clearScheduledPolling, denyAccess, initials, isRejecting, reconcileAfterConfirmation, redirectToLogin, requiresReconciliation]);

  const handleRejection = useCallback(async () => {
    const currentSelection = selectedMealUsageRef.current;
    if (!currentSelection
      || !canPoll()
      || confirmationInFlightRef.current
      || pendingPageRequestInFlightRef.current
      || requiresReconciliation) return;

    confirmationInFlightRef.current = true;
    confirmationNeedsPollingResumeRef.current = true;
    clearScheduledPolling();
    setIsRejecting(true);
    setConfirmationError(null);
    setConfirmationNotice(null);
    setRefreshError(null);
    clearSuccessfulConfirmation();

    try {
      await rejectMealUsage(currentSelection.mealUsageId);
      if (!mountedRef.current) return;
      await reconcileAfterConfirmation("terminal", REJECTION_SUCCESS_NOTICE);
    } catch (error) {
      if (!mountedRef.current) return;
      if (error instanceof UnexpectedRejectionResponseError) {
        await reconcileAfterConfirmation("terminal", "거절 응답을 목록으로 다시 확인했습니다.");
        return;
      }
      if (error instanceof ApiError && error.status === 401) {
        redirectToLogin();
        return;
      }
      if (error instanceof ApiError && error.status === 403) {
        if (error.errorCode === "CSRF_TOKEN_INVALID") {
          setConfirmationError("보안 확인이 만료되었습니다. 다시 거절할 수 있습니다.");
        } else {
          denyAccess();
        }
        return;
      }
      if (error instanceof ApiError
        && (error.status === 404 || error.status === 409 || (error.status > 0 && error.status < 500))) {
        await reconcileAfterConfirmation("terminal", "이미 처리된 거래인지 목록으로 다시 확인했습니다.");
        return;
      }
      await reconcileAfterConfirmation("ambiguous", "거절 결과를 목록으로 확인했습니다. 거래가 아직 확인 대기이면 다시 거절할 수 있습니다.");
    } finally {
      confirmationInFlightRef.current = false;
      if (mountedRef.current) {
        setIsRejecting(false);
      }
      if (confirmationNeedsPollingResumeRef.current && canPoll()) {
        settledRequestCanScheduleRef.current = true;
        settledRequestFailedRef.current = false;
        nextPollingDelayRef.current = POLLING_INTERVAL_MILLIS;
      }
      confirmationNeedsPollingResumeRef.current = false;
      flushRealtimeRefreshRef.current();
    }
  }, [canPoll, clearScheduledPolling, clearSuccessfulConfirmation, denyAccess, reconcileAfterConfirmation, redirectToLogin, requiresReconciliation]);

  useEffect(() => {
    mountedRef.current = true;
    pollingStoppedRef.current = false;

    const pausePolling = () => {
      visibleRef.current = false;
      realtimeRefreshDirtyRef.current = false;
      realtimeRefreshReasonRef.current = "automatic";
      settledRequestCanScheduleRef.current = false;
      clearScheduledPolling();
      invalidatePendingRequests();
      setIsRefreshing(false);
    };
    const resumePolling = () => {
      if (pollingStoppedRef.current) return;
      visibleRef.current = true;
      pollingFailureCountRef.current = 0;
      nextPollingDelayRef.current = POLLING_INTERVAL_MILLIS;
      requestRealtimeRefreshRef.current();
    };
    const onVisibilityChange = () => {
      if (document.hidden) {
        pausePolling();
        return;
      }
      resumePolling();
    };

    if (!document.hidden) {
      resumePolling();
    }
    document.addEventListener("visibilitychange", onVisibilityChange);
    return () => {
      document.removeEventListener("visibilitychange", onVisibilityChange);
      mountedRef.current = false;
      visibleRef.current = false;
      realtimeRefreshDirtyRef.current = false;
      settledRequestCanScheduleRef.current = false;
      clearScheduledPolling();
      invalidatePendingRequests();
    };
  }, [clearScheduledPolling, invalidatePendingRequests]);

  if (viewState === "loading") {
    return (
      <main className={mealUsageListStyles.page} aria-busy="true">
        <section className={mealUsageListStyles.container} aria-label="확인 대기 불러오는 중">
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
    return <StatePanel title="접근 권한이 없습니다" description="이 계정으로는 확인 대기를 볼 수 없습니다." />;
  }

  if (viewState === "error") {
    return <StatePanel title="목록을 불러오지 못했습니다" description="네트워크 상태를 확인한 뒤 다시 시도해 주세요." onRetry={() => void load("initial")} />;
  }

  const rowActionDisabled = isPendingPageLoading || isConfirming || isRejecting || isReconciling || isRefreshing || requiresReconciliation;

  return (
    <main className={mealUsageListStyles.page}>
      <section className={mealUsageListStyles.container} aria-labelledby="pending-title">
        <header className={mealUsageListStyles.header}>
          <p className={mealUsageListStyles.eyebrow}>TIEAT STORE</p>
          <div className={mealUsageListStyles.headerLine}>
            <div className={mealUsageListStyles.titleRow}>
              <h1 id="pending-title" className={mealUsageListStyles.title}>확인 대기</h1>
              <a className={mealUsageListStyles.ledgerLink} href="/store/meal-usages/months">전체 장부</a>
            </div>
            <RefreshButton
              disabled={isRefreshing || isReconciling || isConfirming}
              isRefreshing={isRefreshing}
              onClick={handleManualRefresh}
            />
          </div>
        </header>

        <div className={mealUsageListStyles.card}>
          {refreshError ? <p className={mealUsageListStyles.notice} role="alert">{refreshError}</p> : null}
          {deferredPendingPageChange ? (
            <button className={mealUsageListStyles.incomingRefresh} type="button" onClick={handleManualRefresh} disabled={isRefreshing || isReconciling || isConfirming}>
              {deferredPendingPageChange === "new-request" ? "새로운 요청이 있습니다." : "목록이 변경되었습니다 · 새로고침"}
            </button>
          ) : null}
          {confirmationNotice ? <p className={mealUsageListStyles.statusNotice} role="status">{confirmationNotice}</p> : null}
          {successfulConfirmation ? (
            <div className={mealUsageListStyles.successNotice} role="status">
              <svg aria-hidden="true" className={mealUsageListStyles.successIcon} fill="none" height="20" viewBox="0 0 24 24" width="20">
                <circle cx="12" cy="12" r="8.5" />
                <path d="m8.5 12 2.3 2.3 4.8-5" />
              </svg>
              <div className={mealUsageListStyles.successCopy}>
                <p className={mealUsageListStyles.successTitle}>요청을 확정했습니다.</p>
              </div>
            </div>
          ) : null}
          {viewState === "empty" ? (
            <div className={mealUsageListStyles.state}>
              <h2 className={mealUsageListStyles.stateTitle}>확인 대기가 없습니다</h2>
            </div>
          ) : (
            <>
              <ul className={mealUsageListStyles.list} aria-label="확인 대기 목록">
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
                          <span className={mealUsageListStyles.partner}>
                            {item.partnerDisplayName ?? "미지정"}
                          </span>
                          {item.entrySource === "PARTNER_MOBILE" ? (
                            <span className={mealUsageListStyles.customer}>입력자 {item.customerName ?? "이름 보관기간 만료"}</span>
                          ) : null}
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
                      <dt>협력사</dt>
                      <dd>{selectedMealUsage.partnerDisplayName ?? "미지정"}</dd>
                    </div>
                    {selectedMealUsage.entrySource === "PARTNER_MOBILE" ? (
                      <div>
                        <dt>입력자</dt>
                        <dd>{selectedMealUsage.customerName ?? "이름 보관기간 만료"}</dd>
                      </div>
                    ) : null}
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
                    <div className={mealUsageListStyles.initialsInputRow}>
                      <input
                        aria-describedby={confirmationError ? "confirmation-error" : undefined}
                        aria-invalid={Boolean(confirmationError)}
                        className={mealUsageListStyles.initialsInput}
                        disabled={isPendingPageLoading || isConfirming || isRejecting || isReconciling}
                        id="confirmer-initials"
                        onChange={(event) => setInitials(event.target.value)}
                        ref={initialsInputRef}
                        value={initials}
                      />
                      {!requiresReconciliation ? (
                        <>
                          <button
                            aria-label={isConfirming || isReconciling ? "확정 처리 중…" : "이니셜로 확정"}
                            className={mealUsageListStyles.confirmIconAction}
                            disabled={isPendingPageLoading || isAutomaticPolling || isConfirming || isRejecting || isReconciling || isRefreshing}
                            type="submit"
                          >
                            <svg aria-hidden="true" className={mealUsageListStyles.terminalActionIcon} fill="none" height="22" viewBox="0 0 24 24" width="22">
                              <path d="m5 12 4.1 4.1L19 6.5" />
                            </svg>
                          </button>
                          <button
                            aria-label={isRejecting || isReconciling ? "거절 처리 중…" : "거절"}
                            className={mealUsageListStyles.rejectIconAction}
                            disabled={isPendingPageLoading || isAutomaticPolling || isConfirming || isRejecting || isReconciling || isRefreshing}
                            onClick={() => void handleRejection()}
                            type="button"
                          >
                            <svg aria-hidden="true" className={mealUsageListStyles.terminalActionIcon} fill="none" height="22" viewBox="0 0 24 24" width="22">
                              <path d="m7 7 10 10M17 7 7 17" />
                            </svg>
                          </button>
                        </>
                      ) : null}
                    </div>
                    {confirmationError ? <p id="confirmation-error" className={mealUsageListStyles.confirmationError} role="alert">{confirmationError}</p> : null}
                    {requiresReconciliation ? (
                      <button className={mealUsageListStyles.reconcile} type="button" disabled={isPendingPageLoading || isReconciling || isRefreshing} onClick={() => void reconcileAfterConfirmation(reconciliationMode ?? "ambiguous", "목록을 최신 상태로 반영했습니다.")}>
                        {isReconciling ? "목록 확인 중…" : "목록 다시 불러오기"}
                      </button>
                    ) : null}
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
