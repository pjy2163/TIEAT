"use client";

import { ChangeEvent, KeyboardEvent, useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { ApiError } from "@/lib/store-api";
import {
  downloadConfirmedMealUsagesExport,
  getConfirmedMealUsages,
  type MonthlyMealUsage,
  type ConfirmedMealUsagePage,
} from "@/lib/monthly-meal-usage-api";
import {
  getOutstandingReceivables,
  type OutstandingReceivable,
} from "@/lib/pos-settlement-api";
import { useStorePartnerContext } from "../../StorePartnerContext";
import { StorePartnerScopeBar } from "../../StorePartnerScopeBar";
import { RefreshButton } from "../RefreshButton";
import { PosSettlementRecordDialog, type PosSettlementRecordSeed } from "../../pos-settlements/PosSettlementRecordDialog";
import { monthlyMealUsageListStyles } from "./MonthlyMealUsageList.styles";

const PAGE_SIZE = 20;
const DATE_PATTERN = /^\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$/;

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

function currentKoreanDate(): string {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts();
  const year = parts.find((part) => part.type === "year")?.value;
  const month = parts.find((part) => part.type === "month")?.value;
  const day = parts.find((part) => part.type === "day")?.value;
  return year && month && day ? `${year}-${month}-${day}` : "2026-08-20";
}

function firstDayOfCurrentKoreanMonth(): string {
  return `${currentKoreanDate().slice(0, 7)}-01`;
}

function dateLabel(date: string): string {
  const [year, month, day] = date.split("-");
  return `${year}년 ${Number(month)}월 ${Number(day)}일`;
}

function rangeLabel(fromDate: string, toDate: string): string {
  return fromDate === toDate
    ? dateLabel(fromDate)
    : `${dateLabel(fromDate)}~${dateLabel(toDate)}`;
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError && error.errorCode === "INTERNAL_SERVER_ERROR") {
    return "전체 장부를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
  return "전체 장부를 불러오지 못했습니다. 다시 시도해 주세요.";
}

function exportErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 413) {
    return "내보낼 내역이 10,000건을 초과했습니다. 조회 기간을 줄여 다시 시도해 주세요.";
  }
  return "엑셀 파일을 내려받지 못했습니다. 잠시 후 다시 시도해 주세요.";
}

function settlementStatusLabel(status: MonthlyMealUsage["settlementStatus"]): string | null {
  switch (status) {
    case "PAYMENT_DUE":
      return "결제 전";
    case "PAYMENT_RECORDED":
      return "결제 완료";
    case "PREPAID_SETTLED":
      return "결제 완료(선불)";
    default:
      return null;
  }
}

function isSelectableUsage(item: MonthlyMealUsage): boolean {
  return item.settlementStatus === "PAYMENT_DUE";
}

function selectionMismatchMessage(): string {
  return "선택한 금액이 현재 미수금 목록과 달라 기록할 수 없습니다. 목록을 새로고침한 뒤 다시 선택해 주세요.";
}

export function MonthlyMealUsageList() {
  const router = useRouter();
  const {
    scopeState,
    scopeKey,
    scopeReady,
    selectedMealContractId,
  } = useStorePartnerContext();
  const [fromDate, setFromDate] = useState(firstDayOfCurrentKoreanMonth);
  const [toDate, setToDate] = useState(currentKoreanDate);
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<ConfirmedMealUsagePage | null>(null);
  const [viewState, setViewState] = useState<"loading" | "ready" | "empty" | "forbidden" | "error" | "scope-not-found">("loading");
  const [isLoading, setIsLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [isExporting, setIsExporting] = useState(false);
  const [exportError, setExportError] = useState<string | null>(null);
  const [resultScope, setResultScope] = useState<string | null>(null);
  const [selectedUsageIds, setSelectedUsageIds] = useState<Set<string>>(new Set());
  const [selectedReceivables, setSelectedReceivables] = useState<OutstandingReceivable[]>([]);
  const [selectionState, setSelectionState] = useState<"idle" | "loading" | "ready" | "blocked">("idle");
  const [selectionError, setSelectionError] = useState<string | null>(null);
  const [paymentSource, setPaymentSource] = useState<PosSettlementRecordSeed | null>(null);
  const mountedRef = useRef(false);
  const requestEpochRef = useRef(0);
  const resultRef = useRef<ConfirmedMealUsagePage | null>(null);
  const selectedUsageIdsRef = useRef<Set<string>>(new Set());
  const selectionRequestEpochRef = useRef(0);
  const renderedScopeRef = useRef<string | null>(null);
  const paymentTriggerRef = useRef<HTMLElement | null>(null);
  const routerRef = useRef(router);
  routerRef.current = router;

  const replaceResult = useCallback((next: ConfirmedMealUsagePage | null) => {
    resultRef.current = next;
    setResult(next);
  }, []);

  const closePaymentDialog = useCallback(() => {
    setPaymentSource(null);
    paymentTriggerRef.current?.focus();
  }, []);

  const clearSensitiveRows = useCallback(() => {
    replaceResult(null);
    setResultScope(null);
    setLoadError(null);
    setExportError(null);
    selectedUsageIdsRef.current = new Set();
    setSelectedUsageIds(new Set());
    setSelectedReceivables([]);
    setSelectionState("idle");
    setSelectionError(null);
    selectionRequestEpochRef.current += 1;
    closePaymentDialog();
  }, [closePaymentDialog, replaceResult]);

  const clearSelection = useCallback(() => {
    selectedUsageIdsRef.current = new Set();
    setSelectedUsageIds(new Set());
    setSelectedReceivables([]);
    setSelectionState("idle");
    setSelectionError(null);
    selectionRequestEpochRef.current += 1;
  }, []);

  const validateSelectionReceivables = useCallback((usageIds: Set<string>, items: OutstandingReceivable[]) => {
    const selectedItems = items.filter((item) => usageIds.has(item.mealUsageId));
    const selectedIds = new Set(selectedItems.map((item) => item.mealUsageId));
    const contracts = new Set(selectedItems.map((item) => item.mealContractId));
    if (selectedItems.length !== usageIds.size
      || selectedIds.size !== usageIds.size
      || selectedItems.some((item) => item.receivableCreatedMinor <= 0)
      || contracts.size !== 1) {
      return null;
    }
    return selectedItems;
  }, []);

  const refreshSelectionReceivables = useCallback(async (nextIds: Set<string>) => {
    const requestEpoch = selectionRequestEpochRef.current + 1;
    selectionRequestEpochRef.current = requestEpoch;
    if (nextIds.size === 0) {
      setSelectedReceivables([]);
      setSelectionState("idle");
      setSelectionError(null);
      return;
    }
    setSelectedReceivables([]);
    setSelectionState("loading");
    setSelectionError(null);
    try {
      const overview = await getOutstandingReceivables();
      if (!mountedRef.current || selectionRequestEpochRef.current !== requestEpoch) return;
      const selectedItems = validateSelectionReceivables(nextIds, overview.items);
      if (!selectedItems) {
        setSelectionState("blocked");
        setSelectionError(selectionMismatchMessage());
        return;
      }
      setSelectedReceivables(selectedItems);
      setSelectionState("ready");
    } catch (error) {
      if (!mountedRef.current || selectionRequestEpochRef.current !== requestEpoch) return;
      if (error instanceof ApiError && error.status === 401) {
        clearSensitiveRows();
        setViewState("loading");
        routerRef.current.replace("/store/login?next=/store/meal-usages/months");
        return;
      }
      if (error instanceof ApiError && error.status === 403) {
        clearSensitiveRows();
        setViewState("forbidden");
        return;
      }
      setSelectionState("blocked");
      setSelectionError("선택한 금액을 확인하지 못했습니다. 목록을 새로고침한 뒤 다시 시도해 주세요.");
    }
  }, [clearSensitiveRows, validateSelectionReceivables]);

  const replaceSelection = useCallback((nextIds: Set<string>) => {
    selectedUsageIdsRef.current = nextIds;
    setSelectedUsageIds(nextIds);
    void refreshSelectionReceivables(nextIds);
  }, [refreshSelectionReceivables]);

  const toggleUsageSelection = useCallback((item: MonthlyMealUsage, shouldSelect?: boolean) => {
    if (!isSelectableUsage(item)) return;
    const next = new Set(selectedUsageIdsRef.current);
    const nextSelected = shouldSelect ?? !next.has(item.id);
    if (nextSelected) {
      const currentContractId = selectedReceivables[0]?.mealContractId
        ?? resultRef.current?.items.find((candidate) => selectedUsageIdsRef.current.has(candidate.id))?.mealContractId;
      if (currentContractId && (!item.mealContractId || currentContractId !== item.mealContractId)) {
        setSelectionError("한 번에 한 계약의 결제할 금액만 선택할 수 있습니다.");
        return;
      }
      next.add(item.id);
    } else {
      next.delete(item.id);
    }
    replaceSelection(next);
  }, [replaceSelection, selectedReceivables]);

  const selectCurrentPage = useCallback((onlyDue: boolean) => {
    const current = resultRef.current;
    if (!current) return;
    const candidates = current.items
      .filter((item) => !onlyDue || isSelectableUsage(item))
      .filter(isSelectableUsage);
    const contractIds = new Set(candidates.map((item) => item.mealContractId).filter((id): id is string => Boolean(id)));
    if (contractIds.size > 1) {
      setSelectionState("blocked");
      setSelectionError("한 번에 한 계약의 결제할 금액만 선택할 수 있습니다. 항목을 개별 선택해 같은 계약만 묶어 주세요.");
      return;
    }
    replaceSelection(new Set(candidates.map((item) => item.id)));
  }, [replaceSelection]);

  const openPaymentDialog = useCallback(() => {
    if (selectionState !== "ready" || selectedUsageIds.size === 0 || selectedReceivables.length !== selectedUsageIds.size) {
      setSelectionError(selectionMismatchMessage());
      return;
    }
    const first = selectedReceivables[0];
    if (!first) return;
    paymentTriggerRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setPaymentSource({
      mealContractId: first.mealContractId,
      mealUsageIds: selectedReceivables.map((item) => item.mealUsageId),
      partnerDisplayName: first.partnerDisplayName,
    });
  }, [selectedReceivables, selectedUsageIds, selectionState]);

  const handleDialogSessionExpired = useCallback(() => {
    clearSensitiveRows();
    setViewState("loading");
    routerRef.current.replace("/store/login?next=/store/meal-usages/months");
  }, [clearSensitiveRows]);

  const handleDialogAccessDenied = useCallback(() => {
    clearSensitiveRows();
    setViewState("forbidden");
  }, [clearSensitiveRows]);

  const load = useCallback(async (
    requestedFromDate: string,
    requestedToDate: string,
    requestedPage: number,
    requestedMealContractId: string | null,
  ): Promise<boolean> => {
    const requestEpoch = requestEpochRef.current + 1;
    requestEpochRef.current = requestEpoch;
    clearSelection();
    setIsLoading(true);
    setLoadError(null);
    setExportError(null);
    if (resultRef.current === null) {
      setViewState("loading");
    }

    try {
      const next = requestedMealContractId === null
        ? await getConfirmedMealUsages(requestedFromDate, requestedToDate, requestedPage, PAGE_SIZE)
        : await getConfirmedMealUsages(requestedFromDate, requestedToDate, requestedPage, PAGE_SIZE, requestedMealContractId);
      if (!mountedRef.current || requestEpochRef.current !== requestEpoch) return false;
      replaceResult(next);
      setResultScope(requestedMealContractId);
      setViewState(next.items.length === 0 ? "empty" : "ready");
      return true;
    } catch (error) {
      if (!mountedRef.current || requestEpochRef.current !== requestEpoch) return false;
      if (error instanceof ApiError && error.status === 401) {
        clearSensitiveRows();
        setViewState("loading");
        routerRef.current.replace("/store/login?next=/store/meal-usages/months");
        return false;
      }
      if (error instanceof ApiError && error.status === 403) {
        clearSensitiveRows();
        setViewState("forbidden");
        return false;
      }
      if (requestedMealContractId !== null && error instanceof ApiError && error.status === 404) {
        clearSensitiveRows();
        setViewState("scope-not-found");
        return false;
      }
      if (resultRef.current === null) {
        setViewState("error");
      } else {
        setLoadError(errorMessage(error));
      }
      return false;
    } finally {
      if (mountedRef.current && requestEpochRef.current === requestEpoch) {
        setIsLoading(false);
      }
    }
  }, [clearSensitiveRows, clearSelection, replaceResult]);

  const handleSettlementRecorded = useCallback((): Promise<boolean> => {
    clearSelection();
    return load(fromDate, toDate, page, selectedMealContractId);
  }, [clearSelection, fromDate, load, page, selectedMealContractId, toDate]);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      requestEpochRef.current += 1;
    };
  }, []);

  const scopeIdentity = `${scopeKey}:${scopeState}`;

  useLayoutEffect(() => {
    if (renderedScopeRef.current === scopeIdentity) return;
    renderedScopeRef.current = scopeIdentity;
    requestEpochRef.current += 1;
    closePaymentDialog();
    clearSelection();
    replaceResult(null);
    setResultScope(null);
    setLoadError(null);
    setPage(0);
    setIsLoading(scopeState !== "invalid");
    setViewState(scopeState === "invalid" ? "scope-not-found" : "loading");
  }, [clearSelection, closePaymentDialog, replaceResult, scopeIdentity, scopeState]);

  useEffect(() => {
    if (!scopeReady || scopeState === "invalid") return;
    void load(fromDate, toDate, page, selectedMealContractId);
  }, [fromDate, load, page, scopeReady, scopeState, selectedMealContractId, toDate]);

  const onDateChange = useCallback((event: ChangeEvent<HTMLInputElement>, setDate: (date: string) => void) => {
    const nextDate = event.target.value;
    if (!DATE_PATTERN.test(nextDate)) return;
    clearSelection();
    setExportError(null);
    setDate(nextDate);
    setPage(0);
  }, [clearSelection]);

  const isDisplayingEarlierSafePage = result !== null
    && (result.fromDate !== fromDate
      || result.toDate !== toDate
      || result.page !== page
      || resultScope !== selectedMealContractId);
  const hasNext = result !== null && !isDisplayingEarlierSafePage && result.hasNext;
  const visibleItems = result !== null && !isDisplayingEarlierSafePage ? result.items : [];
  const selectableItems = visibleItems.filter(isSelectableUsage);
  const selectedAmountMinor = selectedReceivables.reduce((total, item) => total + item.receivableCreatedMinor, 0);
  const activeContractId = selectedReceivables[0]?.mealContractId
    ?? visibleItems.find((item) => selectedUsageIds.has(item.id))?.mealContractId
    ?? null;
  const handlePageChange = useCallback((nextPage: number) => {
    clearSelection();
    setPage(Math.max(0, nextPage));
  }, [clearSelection]);

  const handleReload = useCallback(() => {
    clearSelection();
    setExportError(null);
    void load(fromDate, toDate, page, selectedMealContractId);
  }, [clearSelection, fromDate, load, page, selectedMealContractId, toDate]);

  const handleExport = useCallback(async () => {
    setIsExporting(true);
    setExportError(null);
    try {
      const workbook = selectedMealContractId === null
        ? await downloadConfirmedMealUsagesExport(fromDate, toDate)
        : await downloadConfirmedMealUsagesExport(fromDate, toDate, selectedMealContractId);
      const objectUrl = URL.createObjectURL(workbook);
      const link = document.createElement("a");
      link.href = objectUrl;
      link.download = "confirmed-meal-usages.xlsx";
      document.body.appendChild(link);
      try {
        link.click();
      } finally {
        link.remove();
        window.setTimeout(() => URL.revokeObjectURL(objectUrl), 0);
      }
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        clearSensitiveRows();
        setViewState("loading");
        routerRef.current.replace("/store/login?next=/store/meal-usages/months");
        return;
      }
      if (error instanceof ApiError && error.status === 403) {
        clearSensitiveRows();
        setViewState("forbidden");
        return;
      }
      setExportError(exportErrorMessage(error));
    } finally {
      setIsExporting(false);
    }
  }, [clearSensitiveRows, fromDate, selectedMealContractId, toDate]);

  const handleRowKeyDown = useCallback((event: KeyboardEvent<HTMLLIElement>, item: MonthlyMealUsage) => {
    if (!isSelectableUsage(item) || event.target !== event.currentTarget) return;
    if (event.key !== " " && event.key !== "Spacebar" && event.key !== "Enter") return;
    event.preventDefault();
    toggleUsageSelection(item);
  }, [toggleUsageSelection]);

  if (viewState === "loading" && result === null) {
    return (
      <main className={monthlyMealUsageListStyles.page} aria-busy="true">
        <StorePartnerScopeBar />
        <section className={monthlyMealUsageListStyles.container} aria-label="전체 장부 불러오는 중">
          <div className="h-4 w-24 rounded-sm bg-[var(--surface)]" />
          <div className="mt-3 h-9 w-52 rounded-sm bg-[var(--surface)]" />
          <div className={`${monthlyMealUsageListStyles.card} p-5`}>
            {[0, 1, 2].map((item) => <div className={`${monthlyMealUsageListStyles.skeleton} mb-3 h-28 rounded-lg last:mb-0`} key={item} />)}
          </div>
        </section>
      </main>
    );
  }

  if (viewState === "forbidden") {
    return <StatePanel title="접근 권한이 없습니다" description="이 계정으로는 전체 장부를 볼 수 없습니다." />;
  }

  if (viewState === "error") {
    return <StatePanel title="전체 장부를 불러오지 못했습니다" description="네트워크 상태를 확인한 뒤 다시 시도해 주세요." onRetry={() => void load(fromDate, toDate, page, selectedMealContractId)} />;
  }

  if (viewState === "scope-not-found") {
    return (
      <StatePanel
        actionHref="/store/meal-usages/months"
        actionLabel="전체 협력사 보기"
        description="선택한 협력사를 찾을 수 없습니다. 전체 협력사 목록에서 다시 선택해 주세요."
        title="협력사를 찾을 수 없습니다"
      />
    );
  }

  return (
    <main className={monthlyMealUsageListStyles.page}>
      <StorePartnerScopeBar />
      <section className={monthlyMealUsageListStyles.container} aria-labelledby="ledger-title">
        <header className={monthlyMealUsageListStyles.header}>
          <div className={monthlyMealUsageListStyles.titleGroup}>
            <p className={monthlyMealUsageListStyles.eyebrow}>TIEAT STORE</p>
            <div className={monthlyMealUsageListStyles.titleLine}>
              <div className={monthlyMealUsageListStyles.titleRow}>
                <h1 id="ledger-title" className={monthlyMealUsageListStyles.title}>전체 장부</h1>
                <div className={monthlyMealUsageListStyles.titleActions}>
                  <a className={monthlyMealUsageListStyles.settlementLink} href="/store/pos-settlements">결제 내역</a>
                  <a className={monthlyMealUsageListStyles.pendingLink} href="/store/meal-usages">확인 대기로 이동</a>
                </div>
              </div>
            </div>
          </div>
          <div className={monthlyMealUsageListStyles.toolbar}>
            <div className={monthlyMealUsageListStyles.monthFields}>
              <label className={monthlyMealUsageListStyles.field}>
                <span className={monthlyMealUsageListStyles.label}>시작일</span>
                <input aria-label="시작일" className={monthlyMealUsageListStyles.monthInput} type="date" value={fromDate} onChange={(event) => onDateChange(event, setFromDate)} />
              </label>
              <label className={monthlyMealUsageListStyles.field}>
                <span className={monthlyMealUsageListStyles.label}>종료일</span>
                <input aria-label="종료일" className={monthlyMealUsageListStyles.monthInput} type="date" value={toDate} onChange={(event) => onDateChange(event, setToDate)} />
              </label>
            </div>
            <RefreshButton
              className={monthlyMealUsageListStyles.refreshPlacement}
              disabled={isLoading}
              isRefreshing={isLoading}
              onClick={handleReload}
            />
            <div className={monthlyMealUsageListStyles.exportGroup}>
              <button
                className={monthlyMealUsageListStyles.exportButton}
                disabled={isExporting || isLoading || !scopeReady || scopeState === "invalid"}
                onClick={() => void handleExport()}
                type="button"
              >
                {isExporting ? "XLSX 내보내는 중…" : "현재 범위 XLSX 다운로드"}
              </button>
              {exportError ? <p className={monthlyMealUsageListStyles.exportError} role="alert">{exportError}</p> : null}
            </div>
          </div>
        </header>

        <div className={monthlyMealUsageListStyles.card}>
          {loadError ? <p className={monthlyMealUsageListStyles.alert} role="alert">{loadError}</p> : null}
          {isDisplayingEarlierSafePage ? (
            <p className={monthlyMealUsageListStyles.loadingNotice} role="status">
              마지막으로 불러온 {rangeLabel(result.fromDate, result.toDate)} {result.page + 1}페이지를 유지하고 있습니다.
            </p>
          ) : null}
          {isLoading && result !== null ? <p className={monthlyMealUsageListStyles.loadingNotice} role="status">전체 장부를 불러오는 중입니다.</p> : null}
          {result !== null && !isDisplayingEarlierSafePage ? (
            <div className={monthlyMealUsageListStyles.total} aria-label="조회 기간 합계">
              <span className={monthlyMealUsageListStyles.totalLabel}>조회 기간 합계</span>
              <strong className={monthlyMealUsageListStyles.totalAmount}>{amountFormatter.format(result.totalAmountMinor)}</strong>
            </div>
          ) : null}
          {viewState === "empty" ? (
            <div className={monthlyMealUsageListStyles.state}>
              <h2 className={monthlyMealUsageListStyles.stateTitle}>{rangeLabel(fromDate, toDate)} 식대 내역이 없습니다</h2>
            </div>
          ) : (
            <>
              {selectableItems.length > 0 ? (
                <div className={monthlyMealUsageListStyles.pageSelectionControls} aria-label="현재 페이지 선택 도구">
                  <button
                    aria-label={`전체 선택 (현재 페이지 ${visibleItems.length}건)`}
                    className={monthlyMealUsageListStyles.pageSelectionButton}
                    disabled={isLoading}
                    onClick={() => selectCurrentPage(false)}
                    type="button"
                  >
                    전체 선택 <span aria-hidden="true">· {visibleItems.length}건</span>
                  </button>
                  <button
                    aria-label={`미결제 모두 선택 (현재 페이지 ${selectableItems.length}건)`}
                    className={monthlyMealUsageListStyles.pageSelectionButton}
                    disabled={isLoading}
                    onClick={() => selectCurrentPage(true)}
                    type="button"
                  >
                    미결제 선택 <span aria-hidden="true">· {selectableItems.length}건</span>
                  </button>
                </div>
              ) : null}
              <ul className={monthlyMealUsageListStyles.list} aria-label="전체 장부 목록">
                {visibleItems.map((item) => {
                  const selectable = isSelectableUsage(item);
                  const contractLocked = Boolean(activeContractId && item.mealContractId !== activeContractId);
                  const disabled = isLoading || contractLocked;
                  const checked = selectedUsageIds.has(item.id);
                  const statusLabel = settlementStatusLabel(item.settlementStatus);
                  const isCompleted = item.settlementStatus === "PAYMENT_RECORDED" || item.settlementStatus === "PREPAID_SETTLED";
                  return (
                  <li
                    aria-pressed={selectable && !contractLocked ? checked : undefined}
                    className={`${monthlyMealUsageListStyles.row} ${selectable ? monthlyMealUsageListStyles.selectableRow : monthlyMealUsageListStyles.unselectableRow}`}
                    key={item.id}
                    onClick={(event) => {
                      if (!selectable || disabled) return;
                      const target = event.target as HTMLElement;
                      if (target.closest("input,button,a")) return;
                      toggleUsageSelection(item);
                    }}
                    onKeyDown={(event) => {
                      if (disabled) return;
                      handleRowKeyDown(event, item);
                    }}
                    role={selectable && !contractLocked ? "button" : undefined}
                    tabIndex={selectable && !contractLocked ? 0 : undefined}
                  >
                    <div className={monthlyMealUsageListStyles.rowMain}>
                      {selectable ? (
                        <input
                          aria-label={`${item.partnerDisplayName ?? "협력사 정보 미입력"} ${dateFormatter.format(new Date(item.createdAt))} 선택${contractLocked ? " (다른 계약)" : ""}`}
                          checked={checked}
                          className={monthlyMealUsageListStyles.checkbox}
                          disabled={disabled}
                          onChange={(event) => {
                            event.stopPropagation();
                            toggleUsageSelection(item, event.target.checked);
                          }}
                          type="checkbox"
                        />
                      ) : null}
                      <div className={monthlyMealUsageListStyles.rowDetails}>
                        <p className={monthlyMealUsageListStyles.partner}>{item.partnerDisplayName ?? "협력사 정보 미입력"}</p>
                        <div className={monthlyMealUsageListStyles.customerRow}>
                          <p className={monthlyMealUsageListStyles.personMeta}>이름 미입력</p>
                          <div className={monthlyMealUsageListStyles.paymentMeta}>
                            <p className={monthlyMealUsageListStyles.amount}>{amountFormatter.format(item.amountMinor)}</p>
                            <p className={monthlyMealUsageListStyles.confirmedInitials}>확인자 {item.confirmedStaffInitials}</p>
                            {statusLabel ? (
                              <p className={monthlyMealUsageListStyles.settlementStatus}>
                                {isCompleted ? <span aria-hidden="true" className={monthlyMealUsageListStyles.settlementCheck}>✓</span> : null}
                                {statusLabel}
                                {item.settlementStatus === "PAYMENT_RECORDED" ? (
                                  <span className="sr-only">직원이 입력한 POS 정산 내역이 저장된 상태</span>
                                ) : item.settlementStatus === "PREPAID_SETTLED" ? (
                                  <span className="sr-only">선불 잔액으로 처리되어 추가 결제할 금액 없음</span>
                                ) : null}
                              </p>
                            ) : null}
                          </div>
                        </div>
                        <p className={`${monthlyMealUsageListStyles.personMeta} ${monthlyMealUsageListStyles.metadata}`}>
                          {dateFormatter.format(new Date(item.createdAt))}
                        </p>
                      </div>
                    </div>
                  </li>
                  );
                })}
              </ul>
            </>
          )}
          <nav className={monthlyMealUsageListStyles.pager} aria-label="전체 장부 페이지">
            <button className={monthlyMealUsageListStyles.pagerButton} type="button" onClick={() => handlePageChange(page - 1)} disabled={page === 0 || isLoading}>
              이전 페이지
            </button>
            <p className={monthlyMealUsageListStyles.pageLabel}>{page + 1}페이지</p>
            <button className={monthlyMealUsageListStyles.pagerButton} type="button" onClick={() => handlePageChange(page + 1)} disabled={!hasNext || isLoading}>
              다음 페이지
            </button>
          </nav>
        </div>
        {selectedUsageIds.size > 0 ? (
          <aside className={monthlyMealUsageListStyles.selectionBar} aria-label="선택한 결제할 금액 작업">
            <div className={monthlyMealUsageListStyles.selectionBarInner}>
              <div className={monthlyMealUsageListStyles.selectionSummary}>
                <strong>{selectedUsageIds.size}건 선택</strong>
                <span>
                  결제할 금액 {selectionState === "ready"
                    ? amountFormatter.format(selectedAmountMinor)
                    : selectionState === "loading" ? "확인 중…" : "확인 필요"}
                </span>
                {selectionError ? <span className={monthlyMealUsageListStyles.selectionError} role="alert">{selectionError}</span> : null}
              </div>
              <div className={monthlyMealUsageListStyles.selectionActions}>
                <button className={monthlyMealUsageListStyles.clearSelection} onClick={clearSelection} type="button">선택 해제</button>
                <button
                  className={monthlyMealUsageListStyles.selectionSubmit}
                  disabled={selectionState !== "ready" || selectedReceivables.length !== selectedUsageIds.size}
                  onClick={openPaymentDialog}
                  type="button"
                >
                  선택한 결제할 금액 기록하기
                </button>
              </div>
            </div>
          </aside>
        ) : null}
        {paymentSource ? (
          <PosSettlementRecordDialog
            onAccessDenied={handleDialogAccessDenied}
            onClose={closePaymentDialog}
            onSettlementRecorded={handleSettlementRecorded}
            onSessionExpired={handleDialogSessionExpired}
            seed={paymentSource}
          />
        ) : null}
      </section>
    </main>
  );
}

function StatePanel({
  actionHref,
  actionLabel,
  title,
  description,
  onRetry,
}: {
  actionHref?: string;
  actionLabel?: string;
  title: string;
  description: string;
  onRetry?: () => void;
}) {
  return (
    <main className={monthlyMealUsageListStyles.page}>
      <StorePartnerScopeBar />
      <section className={`${monthlyMealUsageListStyles.container} ${monthlyMealUsageListStyles.card} ${monthlyMealUsageListStyles.state}`} aria-live="polite">
        <h1 className={monthlyMealUsageListStyles.stateTitle}>{title}</h1>
        <p className={monthlyMealUsageListStyles.stateDescription}>{description}</p>
        {onRetry ? <button className={monthlyMealUsageListStyles.stateAction} type="button" onClick={onRetry}>다시 시도</button> : null}
        {actionHref && actionLabel ? <a className={monthlyMealUsageListStyles.stateAction} href={actionHref}>{actionLabel}</a> : null}
      </section>
    </main>
  );
}
