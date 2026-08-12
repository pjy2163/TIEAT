"use client";

import { ChangeEvent, useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { ApiError } from "@/lib/store-api";
import {
  getMonthlyMealUsages,
  type MonthlyMealUsage,
  type MonthlyMealUsagePage,
} from "@/lib/monthly-meal-usage-api";
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

function errorMessage(error: unknown): string {
  if (error instanceof ApiError && error.errorCode === "INTERNAL_SERVER_ERROR") {
    return "월별 장부를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
  return "월별 장부를 불러오지 못했습니다. 다시 시도해 주세요.";
}

export function MonthlyMealUsageList() {
  const router = useRouter();
  const [month, setMonth] = useState(currentKoreanMonth);
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<MonthlyMealUsagePage | null>(null);
  const [viewState, setViewState] = useState<"loading" | "ready" | "empty" | "forbidden" | "error">("loading");
  const [isLoading, setIsLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const mountedRef = useRef(false);
  const requestEpochRef = useRef(0);
  const resultRef = useRef<MonthlyMealUsagePage | null>(null);
  const routerRef = useRef(router);
  routerRef.current = router;

  const replaceResult = useCallback((next: MonthlyMealUsagePage | null) => {
    resultRef.current = next;
    setResult(next);
  }, []);

  const clearSensitiveRows = useCallback(() => {
    replaceResult(null);
    setLoadError(null);
  }, [replaceResult]);

  const load = useCallback(async (requestedMonth: string, requestedPage: number) => {
    const requestEpoch = requestEpochRef.current + 1;
    requestEpochRef.current = requestEpoch;
    setIsLoading(true);
    setLoadError(null);
    if (resultRef.current === null) {
      setViewState("loading");
    }

    try {
      const next = await getMonthlyMealUsages(requestedMonth, requestedPage, PAGE_SIZE);
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
  }, [clearSensitiveRows, replaceResult]);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      requestEpochRef.current += 1;
    };
  }, []);

  useEffect(() => {
    void load(month, page);
  }, [load, month, page]);

  const onMonthChange = useCallback((event: ChangeEvent<HTMLInputElement>) => {
    const nextMonth = event.target.value;
    if (!YEAR_MONTH_PATTERN.test(nextMonth)) return;
    setMonth(nextMonth);
    setPage(0);
  }, []);

  const isDisplayingEarlierSafePage = result !== null && (result.month !== month || result.page !== page);
  const hasNext = result !== null && !isDisplayingEarlierSafePage && result.hasNext;

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
    return <StatePanel title="월별 장부를 불러오지 못했습니다" description="네트워크 상태를 확인한 뒤 다시 시도해 주세요." onRetry={() => void load(month, page)} />;
  }

  return (
    <main className={monthlyMealUsageListStyles.page}>
      <section className={monthlyMealUsageListStyles.container} aria-labelledby="monthly-ledger-title">
        <header className={monthlyMealUsageListStyles.header}>
          <div>
            <p className={monthlyMealUsageListStyles.eyebrow}>TIEAT STORE</p>
            <h1 id="monthly-ledger-title" className={monthlyMealUsageListStyles.title}>월별 장부</h1>
            <p className={monthlyMealUsageListStyles.description}>입력 시각 기준으로 해당 월의 식대 내역을 표시합니다.</p>
          </div>
          <div className={monthlyMealUsageListStyles.controls}>
            <label className={monthlyMealUsageListStyles.field}>
              <span className={monthlyMealUsageListStyles.label}>조회 월</span>
              <input className={monthlyMealUsageListStyles.monthInput} type="month" value={month} onChange={onMonthChange} />
            </label>
            <button className={monthlyMealUsageListStyles.reload} type="button" onClick={() => void load(month, page)} disabled={isLoading}>
              {isLoading ? "불러오는 중…" : "다시 불러오기"}
            </button>
            <a className={monthlyMealUsageListStyles.pendingLink} href="/store/meal-usages">확인 대기로 이동</a>
          </div>
        </header>

        <div className={monthlyMealUsageListStyles.card}>
          {loadError ? <p className={monthlyMealUsageListStyles.alert} role="alert">{loadError}</p> : null}
          {isDisplayingEarlierSafePage ? (
            <p className={monthlyMealUsageListStyles.loadingNotice} role="status">
              마지막으로 불러온 {monthLabel(result.month)} {result.page + 1}페이지를 유지하고 있습니다.
            </p>
          ) : null}
          {isLoading && result !== null ? <p className={monthlyMealUsageListStyles.loadingNotice} role="status">월별 장부를 불러오는 중입니다.</p> : null}
          {viewState === "empty" ? (
            <div className={monthlyMealUsageListStyles.state}>
              <h2 className={monthlyMealUsageListStyles.stateTitle}>{monthLabel(month)} 식대 내역이 없습니다</h2>
              <p className={monthlyMealUsageListStyles.stateDescription}>해당 월에 표시할 식대 사용 내역이 없습니다.</p>
            </div>
          ) : (
            <ul className={monthlyMealUsageListStyles.list} aria-label="월별 장부 목록">
              {result?.items.map((item) => (
                <li className={monthlyMealUsageListStyles.row} key={item.id}>
                  <div>
                    <p className={monthlyMealUsageListStyles.partner}>{item.partnerDisplayName ?? "협력사 정보 미입력"}</p>
                    <p className={monthlyMealUsageListStyles.customer}><span>입력자</span>이름 미입력</p>
                  </div>
                  <div className={monthlyMealUsageListStyles.side}>
                    <p className={monthlyMealUsageListStyles.amount}>{amountFormatter.format(item.amountMinor)}</p>
                    <div className={monthlyMealUsageListStyles.statusBlock}>
                      <p className={monthlyMealUsageListStyles.confirmedInitials}>확인자 {item.confirmedStaffInitials}</p>
                    </div>
                  </div>
                  <div className={monthlyMealUsageListStyles.metadata}>
                    <p><span className={monthlyMealUsageListStyles.metadataLabel}>입력 시각</span>{dateFormatter.format(new Date(item.createdAt))}</p>
                  </div>
                </li>
              ))}
            </ul>
          )}
          <nav className={monthlyMealUsageListStyles.pager} aria-label="월별 장부 페이지">
            <button className={monthlyMealUsageListStyles.pagerButton} type="button" onClick={() => setPage((current) => Math.max(0, current - 1))} disabled={page === 0 || isLoading}>
              이전 페이지
            </button>
            <p className={monthlyMealUsageListStyles.pageLabel}>{page + 1}페이지</p>
            <button className={monthlyMealUsageListStyles.pagerButton} type="button" onClick={() => setPage((current) => current + 1)} disabled={!hasNext || isLoading}>
              다음 페이지
            </button>
          </nav>
        </div>
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
