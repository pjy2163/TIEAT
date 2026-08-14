"use client";

import { ChangeEvent, KeyboardEvent, MouseEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { ApiError } from "@/lib/store-api";
import {
  getMonthlyMealUsages,
  type MonthlyMealUsage,
  type MonthlyMealUsagePage,
} from "@/lib/monthly-meal-usage-api";
import {
  discardPosSettlementSelectionSeed,
  getOutstandingReceivables,
  writePosSettlementSelectionSeed,
  type OutstandingReceivable,
} from "@/lib/pos-settlement-api";
import { monthlyMealUsageListStyles } from "./MonthlyMealUsageList.styles";

const PAGE_SIZE = 20;
const YEAR_MONTH_PATTERN = /^\d{4}-(0[1-9]|1[0-2])$/;

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

function currentKoreanMonth(): string {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
  }).formatToParts();
  const year = parts.find((part) => part.type === "year")?.value;
  const month = parts.find((part) => part.type === "month")?.value;
  return year && month ? `${year}-${month}` : "2026-08";
}

function monthLabel(month: string): string {
  const [year, monthNumber] = month.split("-");
  return `${year}년 ${Number(monthNumber)}월`;
}

function rangeLabel(fromMonth: string, toMonth: string): string {
  return fromMonth === toMonth
    ? monthLabel(fromMonth)
    : `${monthLabel(fromMonth)}~${monthLabel(toMonth)}`;
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError && error.errorCode === "INTERNAL_SERVER_ERROR") {
    return "월별 장부를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
  return "월별 장부를 불러오지 못했습니다. 다시 시도해 주세요.";
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
  return "선택한 월별 금액이 현재 미수금 목록과 달라 기록할 수 없습니다. 목록을 새로고침한 뒤 다시 선택해 주세요.";
}

export function MonthlyMealUsageList() {
  const router = useRouter();
  const [fromMonth, setFromMonth] = useState(currentKoreanMonth);
  const [toMonth, setToMonth] = useState(currentKoreanMonth);
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<MonthlyMealUsagePage | null>(null);
  const [viewState, setViewState] = useState<"loading" | "ready" | "empty" | "forbidden" | "error">("loading");
  const [isLoading, setIsLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [selectedUsageIds, setSelectedUsageIds] = useState<Set<string>>(new Set());
  const [selectedReceivables, setSelectedReceivables] = useState<OutstandingReceivable[]>([]);
  const [selectionState, setSelectionState] = useState<"idle" | "loading" | "ready" | "blocked">("idle");
  const [selectionError, setSelectionError] = useState<string | null>(null);
  const mountedRef = useRef(false);
  const requestEpochRef = useRef(0);
  const resultRef = useRef<MonthlyMealUsagePage | null>(null);
  const selectedUsageIdsRef = useRef<Set<string>>(new Set());
  const selectionRequestEpochRef = useRef(0);
  const routerRef = useRef(router);
  routerRef.current = router;

  const replaceResult = useCallback((next: MonthlyMealUsagePage | null) => {
    resultRef.current = next;
    setResult(next);
  }, []);

  const clearSensitiveRows = useCallback(() => {
    replaceResult(null);
    setLoadError(null);
    discardPosSettlementSelectionSeed();
    selectedUsageIdsRef.current = new Set();
    setSelectedUsageIds(new Set());
    setSelectedReceivables([]);
    setSelectionState("idle");
    setSelectionError(null);
    selectionRequestEpochRef.current += 1;
  }, [replaceResult]);

  const clearSelection = useCallback(() => {
    discardPosSettlementSelectionSeed();
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
    if (nextSelected) next.add(item.id);
    else next.delete(item.id);
    replaceSelection(next);
  }, [replaceSelection]);

  const selectCurrentPage = useCallback((onlyDue: boolean) => {
    const current = resultRef.current;
    if (!current) return;
    const ids = current.items
      .filter((item) => !onlyDue || isSelectableUsage(item))
      .filter(isSelectableUsage)
      .map((item) => item.id);
    replaceSelection(new Set(ids));
  }, [replaceSelection]);

  const handoffSelection = useCallback((event: MouseEvent<HTMLAnchorElement>) => {
    if (selectionState !== "ready" || selectedUsageIds.size === 0 || selectedReceivables.length !== selectedUsageIds.size) {
      event.preventDefault();
      return;
    }
    const mealContractId = selectedReceivables[0]?.mealContractId ?? null;
    const amountMinor = selectedReceivables.reduce((total, item) => total + item.receivableCreatedMinor, 0);
    const written = writePosSettlementSelectionSeed({
      mealUsageIds: Array.from(selectedUsageIds),
      mealContractId,
      amountMinor,
    });
    if (!written) {
      event.preventDefault();
      setSelectionState("blocked");
      setSelectionError("선택한 금액을 다음 화면으로 안전하게 전달하지 못했습니다. 다시 선택해 주세요.");
    }
  }, [selectedReceivables, selectedUsageIds, selectionState]);

  const load = useCallback(async (requestedFromMonth: string, requestedToMonth: string, requestedPage: number) => {
    const requestEpoch = requestEpochRef.current + 1;
    requestEpochRef.current = requestEpoch;
    clearSelection();
    setIsLoading(true);
    setLoadError(null);
    if (resultRef.current === null) {
      setViewState("loading");
    }

    try {
      const next = await getMonthlyMealUsages(requestedFromMonth, requestedToMonth, requestedPage, PAGE_SIZE);
      if (!mountedRef.current || requestEpochRef.current !== requestEpoch) return;
      replaceResult(next);
      setViewState(next.items.length === 0 ? "empty" : "ready");
    } catch (error) {
      if (!mountedRef.current || requestEpochRef.current !== requestEpoch) return;
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
      if (resultRef.current === null) {
        setViewState("error");
      } else {
        setLoadError(errorMessage(error));
      }
    } finally {
      if (mountedRef.current && requestEpochRef.current === requestEpoch) {
        setIsLoading(false);
      }
    }
  }, [clearSensitiveRows, clearSelection, replaceResult]);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      requestEpochRef.current += 1;
    };
  }, []);

  useEffect(() => {
    void load(fromMonth, toMonth, page);
  }, [fromMonth, load, page, toMonth]);

  const onMonthChange = useCallback((event: ChangeEvent<HTMLInputElement>, setMonth: (month: string) => void) => {
    const nextMonth = event.target.value;
    if (!YEAR_MONTH_PATTERN.test(nextMonth)) return;
    clearSelection();
    setMonth(nextMonth);
    setPage(0);
  }, [clearSelection]);

  const isDisplayingEarlierSafePage = result !== null
    && (result.fromMonth !== fromMonth || result.toMonth !== toMonth || result.page !== page);
  const hasNext = result !== null && !isDisplayingEarlierSafePage && result.hasNext;
  const visibleItems = result !== null && !isDisplayingEarlierSafePage ? result.items : [];
  const selectableItems = useMemo(() => visibleItems.filter(isSelectableUsage), [visibleItems]);
  const selectedAmountMinor = selectedReceivables.reduce((total, item) => total + item.receivableCreatedMinor, 0);

  const handlePageChange = useCallback((nextPage: number) => {
    clearSelection();
    setPage(Math.max(0, nextPage));
  }, [clearSelection]);

  const handleReload = useCallback(() => {
    clearSelection();
    void load(fromMonth, toMonth, page);
  }, [clearSelection, fromMonth, load, page, toMonth]);

  const handleRowKeyDown = useCallback((event: KeyboardEvent<HTMLLIElement>, item: MonthlyMealUsage) => {
    if (!isSelectableUsage(item) || event.target !== event.currentTarget) return;
    if (event.key !== " " && event.key !== "Spacebar") return;
    event.preventDefault();
    toggleUsageSelection(item);
  }, [toggleUsageSelection]);

  if (viewState === "loading" && result === null) {
    return (
      <main className={monthlyMealUsageListStyles.page} aria-busy="true">
        <section className={monthlyMealUsageListStyles.container} aria-label="월별 장부 불러오는 중">
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
    return <StatePanel title="접근 권한이 없습니다" description="이 계정으로는 월별 장부를 볼 수 없습니다." />;
  }

  if (viewState === "error") {
    return <StatePanel title="월별 장부를 불러오지 못했습니다" description="네트워크 상태를 확인한 뒤 다시 시도해 주세요." onRetry={() => void load(fromMonth, toMonth, page)} />;
  }

  return (
    <main className={monthlyMealUsageListStyles.page}>
      <section className={monthlyMealUsageListStyles.container} aria-labelledby="monthly-ledger-title">
        <header className={monthlyMealUsageListStyles.header}>
          <div>
            <p className={monthlyMealUsageListStyles.eyebrow}>TIEAT STORE</p>
            <h1 id="monthly-ledger-title" className={monthlyMealUsageListStyles.title}>월별 장부</h1>
            <p className={monthlyMealUsageListStyles.description}>
              월별 장부는 사용 이력입니다. 결제할 금액은 월과 관계없이 따로 확인하세요.
            </p>
          </div>
          <div className={monthlyMealUsageListStyles.controls}>
            <label className={monthlyMealUsageListStyles.field}>
              <span className={monthlyMealUsageListStyles.label}>시작 월</span>
              <input className={monthlyMealUsageListStyles.monthInput} type="month" value={fromMonth} onChange={(event) => onMonthChange(event, setFromMonth)} />
            </label>
            <label className={monthlyMealUsageListStyles.field}>
              <span className={monthlyMealUsageListStyles.label}>종료 월</span>
              <input className={monthlyMealUsageListStyles.monthInput} type="month" value={toMonth} onChange={(event) => onMonthChange(event, setToMonth)} />
            </label>
            <button className={monthlyMealUsageListStyles.reload} type="button" onClick={handleReload} disabled={isLoading}>
              {isLoading ? "불러오는 중…" : "다시 불러오기"}
            </button>
            <a className={monthlyMealUsageListStyles.settlementLink} href="/store/pos-settlements">결제할 금액 보기</a>
            <a className={monthlyMealUsageListStyles.pendingLink} href="/store/meal-usages">확인 대기로 이동</a>
          </div>
        </header>

        <div className={monthlyMealUsageListStyles.card}>
          {loadError ? <p className={monthlyMealUsageListStyles.alert} role="alert">{loadError}</p> : null}
          {isDisplayingEarlierSafePage ? (
            <p className={monthlyMealUsageListStyles.loadingNotice} role="status">
              마지막으로 불러온 {rangeLabel(result.fromMonth, result.toMonth)} {result.page + 1}페이지를 유지하고 있습니다.
            </p>
          ) : null}
          {isLoading && result !== null ? <p className={monthlyMealUsageListStyles.loadingNotice} role="status">월별 장부를 불러오는 중입니다.</p> : null}
          {result !== null ? (
            <div className={monthlyMealUsageListStyles.total} aria-label="조회 기간 합계">
              <span className={monthlyMealUsageListStyles.totalLabel}>조회 기간 합계</span>
              <strong className={monthlyMealUsageListStyles.totalAmount}>{amountFormatter.format(result.totalAmountMinor)}</strong>
            </div>
          ) : null}
          {viewState === "empty" ? (
            <div className={monthlyMealUsageListStyles.state}>
              <h2 className={monthlyMealUsageListStyles.stateTitle}>{rangeLabel(fromMonth, toMonth)} 식대 내역이 없습니다</h2>
              <p className={monthlyMealUsageListStyles.stateDescription}>해당 기간에 표시할 식대 사용 내역이 없습니다.</p>
            </div>
          ) : (
            <>
              {selectableItems.length > 0 ? (
                <div className={monthlyMealUsageListStyles.pageSelectionControls} aria-label="현재 페이지 선택 도구">
                  <button
                    className={monthlyMealUsageListStyles.pageSelectionButton}
                    disabled={isLoading}
                    onClick={() => selectCurrentPage(false)}
                    type="button"
                  >
                    전체 선택 (현재 페이지 {visibleItems.length}건)
                  </button>
                  <button
                    className={monthlyMealUsageListStyles.pageSelectionButton}
                    disabled={isLoading}
                    onClick={() => selectCurrentPage(true)}
                    type="button"
                  >
                    미결제 모두 선택 (현재 페이지 {selectableItems.length}건)
                  </button>
                </div>
              ) : null}
              <ul className={monthlyMealUsageListStyles.list} aria-label="월별 장부 목록">
                {result?.items.map((item) => {
                  const selectable = isSelectableUsage(item);
                  const checked = selectedUsageIds.has(item.id);
                  return (
                  <li
                    aria-pressed={selectable ? checked : undefined}
                    className={`${monthlyMealUsageListStyles.row} ${selectable ? monthlyMealUsageListStyles.selectableRow : monthlyMealUsageListStyles.unselectableRow}`}
                    key={item.id}
                    onClick={(event) => {
                      if (!selectable) return;
                      const target = event.target as HTMLElement;
                      if (target.closest("input,button,a")) return;
                      toggleUsageSelection(item);
                    }}
                    onKeyDown={(event) => handleRowKeyDown(event, item)}
                    role={selectable ? "button" : undefined}
                    tabIndex={selectable ? 0 : undefined}
                  >
                    <div className={monthlyMealUsageListStyles.rowMain}>
                      {selectable ? (
                        <input
                          aria-label={`${item.partnerDisplayName ?? "협력사 정보 미입력"} ${dateFormatter.format(new Date(item.createdAt))} 선택`}
                          checked={checked}
                          className={monthlyMealUsageListStyles.checkbox}
                          onChange={(event) => {
                            event.stopPropagation();
                            toggleUsageSelection(item, event.target.checked);
                          }}
                          type="checkbox"
                        />
                      ) : null}
                      <div>
                      <p className={monthlyMealUsageListStyles.partner}>{item.partnerDisplayName ?? "협력사 정보 미입력"}</p>
                      <p className={monthlyMealUsageListStyles.customer}><span>입력자</span>이름 미입력</p>
                      </div>
                    </div>
                    <div className={monthlyMealUsageListStyles.side}>
                      <p className={monthlyMealUsageListStyles.amount}>{amountFormatter.format(item.amountMinor)}</p>
                      <div className={monthlyMealUsageListStyles.statusBlock}>
                        <p className={monthlyMealUsageListStyles.confirmedInitials}>확인자 {item.confirmedStaffInitials}</p>
                        {settlementStatusLabel(item.settlementStatus) ? (
                          <>
                            <p className={monthlyMealUsageListStyles.settlementStatus}>
                              {settlementStatusLabel(item.settlementStatus)}
                            </p>
                            {item.settlementStatus === "PAYMENT_RECORDED" ? (
                              <span className="sr-only">직원이 입력한 POS 정산 내역이 저장된 상태</span>
                            ) : item.settlementStatus === "PREPAID_SETTLED" ? (
                              <span className="sr-only">선불 잔액으로 처리되어 추가 결제할 금액 없음</span>
                            ) : null}
                          </>
                        ) : null}
                      </div>
                    </div>
                    <div className={monthlyMealUsageListStyles.metadata}>
                      <p><span className={monthlyMealUsageListStyles.metadataLabel}>입력 시각</span>{dateFormatter.format(new Date(item.createdAt))}</p>
                    </div>
                  </li>
                  );
                })}
              </ul>
            </>
          )}
          <nav className={monthlyMealUsageListStyles.pager} aria-label="월별 장부 페이지">
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
                <a
                  className={monthlyMealUsageListStyles.selectionSubmit}
                  href="/store/pos-settlements"
                  onClick={handoffSelection}
                >
                  선택한 결제할 금액 기록하기
                </a>
              </div>
            </div>
          </aside>
        ) : null}
      </section>
    </main>
  );
}

function StatePanel({ title, description, onRetry }: { title: string; description: string; onRetry?: () => void }) {
  return (
    <main className={monthlyMealUsageListStyles.page}>
      <section className={`${monthlyMealUsageListStyles.container} ${monthlyMealUsageListStyles.card} ${monthlyMealUsageListStyles.state}`} aria-live="polite">
        <h1 className={monthlyMealUsageListStyles.stateTitle}>{title}</h1>
        <p className={monthlyMealUsageListStyles.stateDescription}>{description}</p>
        {onRetry ? <button className={monthlyMealUsageListStyles.stateAction} type="button" onClick={onRetry}>다시 시도</button> : null}
      </section>
    </main>
  );
}
