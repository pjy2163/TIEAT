"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { FormEvent } from "react";
import { useRouter } from "next/navigation";
import { ApiError } from "@/lib/store-api";
import {
  getRecentPosSettlements,
  type PosSettlement,
  type PosSettlementHistoryFilters,
  type PosSettlementHistoryPage,
  type PosSettlementReceipt,
  type PosSettlementReceiptSummary,
} from "@/lib/pos-settlement-api";
import { useStorePartnerContext } from "../StorePartnerContext";
import { StorePagination } from "../StorePagination";
import { posSettlementFormStyles } from "./PosSettlementForm.styles";
import { ReceiptAttachment } from "./ReceiptAttachment";
import { ReceiptDownload } from "./ReceiptDownload";
import { ReceiptPreview } from "./ReceiptPreview";

type HistoryViewState = "loading" | "ready" | "empty" | "error";
type AccessState = "allowed" | "forbidden";

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

const EMPTY_RECEIPT_SUMMARY: PosSettlementReceiptSummary = {
  status: "NONE",
  fileName: null,
  contentType: null,
  sizeBytes: null,
  uploadedAt: null,
  expiresAt: null,
};

function receiptSummaryOf(record: PosSettlement): PosSettlementReceiptSummary {
  return record.receipt ?? EMPTY_RECEIPT_SUMMARY;
}

function receiptStatusLabel(status: PosSettlementReceiptSummary["status"]): string {
  if (status === "AVAILABLE") return "영수증 있음";
  if (status === "EXPIRED") return "영수증 만료";
  return "영수증 없음";
}

function receiptSummaryFromUpload(receipt: PosSettlementReceipt): PosSettlementReceiptSummary {
  return {
    status: "AVAILABLE",
    fileName: receipt.fileName,
    contentType: receipt.contentType,
    sizeBytes: receipt.sizeBytes,
    uploadedAt: receipt.uploadedAt,
    expiresAt: receipt.expiresAt,
  };
}

function partnerNamesOf(record: PosSettlement): string[] {
  return Array.from(new Set(
    record.allocations
      .map((allocation) => allocation.partnerDisplayName?.trim())
      .filter((name): name is string => Boolean(name)),
  )).sort((left, right) => left.localeCompare(right, "ko"));
}

function partnerLabelOf(record: PosSettlement): string {
  const names = partnerNamesOf(record);
  return names.length === 0 ? "정보 없음" : names.join(" · ");
}

function StatePanel({
  title,
  description,
  onRetry,
}: {
  title: string;
  description: string;
  onRetry?: () => void;
}) {
  return (
    <main className={posSettlementFormStyles.page}>
      <section className={posSettlementFormStyles.container + " " + posSettlementFormStyles.card + " " + posSettlementFormStyles.state}>
        <h1 className={posSettlementFormStyles.stateTitle}>{title}</h1>
        <p className={posSettlementFormStyles.stateDescription}>{description}</p>
        {onRetry ? <button className={posSettlementFormStyles.stateAction} onClick={onRetry} type="button">다시 시도</button> : null}
      </section>
    </main>
  );
}

function ReceiptSummaryView({
  record,
  onUploaded,
}: {
  record: PosSettlement;
  onUploaded: (receipt: PosSettlementReceipt) => void;
}) {
  const receipt = receiptSummaryOf(record);
  const canDownload = receipt.status === "AVAILABLE" && Boolean(record.posSettlementId);

  if (receipt.status === "NONE" && record.posSettlementId) {
    return <ReceiptAttachment onUploaded={onUploaded} posSettlementId={record.posSettlementId} />;
  }

  return (
    <div className={posSettlementFormStyles.historyReceipt} aria-label="영수증 상태">
      <p className={posSettlementFormStyles.historyReceiptStatus}>{receiptStatusLabel(receipt.status)}</p>
      {receipt.status !== "NONE" && receipt.fileName ? (
        <p className={posSettlementFormStyles.historyReceiptMeta}>{receipt.fileName}</p>
      ) : null}
      {receipt.status === "AVAILABLE" && receipt.contentType && record.posSettlementId ? (
        <ReceiptPreview
          contentType={receipt.contentType}
          fileName={receipt.fileName}
          posSettlementId={record.posSettlementId}
        />
      ) : null}
      {canDownload ? (
        <ReceiptDownload posSettlementId={record.posSettlementId!} fileName={receipt.fileName} />
      ) : null}
    </div>
  );
}

function HistoryItem({ record, index, expanded, onReceiptUploaded, onToggle }: {
  record: PosSettlement;
  index: number;
  expanded: boolean;
  onReceiptUploaded: (receipt: PosSettlementReceipt) => void;
  onToggle: () => void;
}) {
  const detailId = "pos-settlement-detail-" + index;

  return (
    <li className={posSettlementFormStyles.historyItem}>
      <article>
        <button
          aria-controls={detailId}
          aria-expanded={expanded}
          aria-label={partnerLabelOf(record) + " · 결제일 " + record.posBusinessDate + (expanded ? " 상세 닫기" : " 상세 보기")}
          className={posSettlementFormStyles.historyToggle}
          onClick={onToggle}
          type="button"
        >
          <p className={posSettlementFormStyles.historyRecord}>{partnerLabelOf(record)}</p>
          <h2 className={posSettlementFormStyles.historyItemTitle}>결제일 {record.posBusinessDate}</h2>
          <p className={posSettlementFormStyles.historyItemMeta}>
            결제 금액 {amountFormatter.format(record.submittedTotalMinor)} · 상세 {expanded ? "닫기" : "보기"}
          </p>
        </button>
        {expanded ? (
          <div className={posSettlementFormStyles.historyDetail} id={detailId}>
            <dl className={posSettlementFormStyles.historyDetailGrid}>
              <div>
                <dt>협력사</dt>
                <dd>{partnerLabelOf(record)}</dd>
              </div>
              <div>
                <dt>결제일</dt>
                <dd>{record.posBusinessDate}</dd>
              </div>
              <div>
                <dt>결제 금액</dt>
                <dd>{amountFormatter.format(record.submittedTotalMinor)}</dd>
              </div>
              <div>
                <dt>기록 시각</dt>
                <dd>{dateFormatter.format(new Date(record.recordedAt))}</dd>
              </div>
              <div>
                <dt>결제 확인자</dt>
                <dd>{record.recordedByLoginId ?? "확인자 정보 없음"}</dd>
              </div>
            </dl>
            <ul className={posSettlementFormStyles.historyAllocationList} aria-label={"결제일 " + record.posBusinessDate + " 결제 기록에 포함된 금액"}>
              {record.allocations.map((allocation, allocationIndex) => (
                <li key={allocationIndex}>
                  <span className={posSettlementFormStyles.historyAllocationUsage}>
                    {allocation.partnerDisplayName ?? "협력사 정보 없음"}
                    <span className={posSettlementFormStyles.secondary}>
                      {dateFormatter.format(new Date(allocation.confirmedAt))}
                    </span>
                  </span>
                  <strong>{amountFormatter.format(allocation.receivableAmountMinor)}</strong>
                </li>
              ))}
            </ul>
            <ReceiptSummaryView onUploaded={onReceiptUploaded} record={record} />
          </div>
        ) : null}
      </article>
    </li>
  );
}

export function PosSettlementForm() {
  const router = useRouter();
  const { partners } = useStorePartnerContext();
  const [accessState, setAccessState] = useState<AccessState>("allowed");
  const [historyViewState, setHistoryViewState] = useState<HistoryViewState>("loading");
  const [history, setHistory] = useState<PosSettlement[]>([]);
  const [page, setPage] = useState(0);
  const [hasNext, setHasNext] = useState(false);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [expandedIndex, setExpandedIndex] = useState<number | null>(null);
  const [selectedPartnerName, setSelectedPartnerName] = useState("");
  const [searchInput, setSearchInput] = useState("");
  const [appliedFilters, setAppliedFilters] = useState<PosSettlementHistoryFilters>({});
  const requestEpochRef = useRef(0);
  const initialLoadStartedRef = useRef(false);

  const partnerOptions = useMemo(() => {
    const names = new Set(partners.map((partner) => partner.partnerDisplayName));
    history.forEach((record) => partnerNamesOf(record).forEach((name) => names.add(name)));
    return Array.from(names).sort((left, right) => left.localeCompare(right, "ko"));
  }, [history, partners]);
  const hasActiveFilter = Boolean(appliedFilters.partnerDisplayName || appliedFilters.search);

  const clearSensitiveState = useCallback(() => {
    setHistory([]);
    setPage(0);
    setHasNext(false);
    setExpandedIndex(null);
    setSelectedPartnerName("");
    setSearchInput("");
    setAppliedFilters({});
  }, []);

  const redirectToLogin = useCallback(() => {
    requestEpochRef.current += 1;
    clearSensitiveState();
    setHistoryViewState("loading");
    router.replace("/store/login?next=/store/pos-settlements");
  }, [clearSensitiveState, router]);

  const denyAccess = useCallback(() => {
    requestEpochRef.current += 1;
    clearSensitiveState();
    setAccessState("forbidden");
  }, [clearSensitiveState]);

  const loadHistory = useCallback(async (
    targetPage: number,
    manual = false,
    filters: PosSettlementHistoryFilters = appliedFilters,
  ) => {
    const requestEpoch = requestEpochRef.current + 1;
    requestEpochRef.current = requestEpoch;
    if (manual) {
      setIsRefreshing(true);
    } else {
      setHistoryViewState("loading");
    }
    try {
      const result: PosSettlementHistoryPage = await getRecentPosSettlements(targetPage, filters);
      if (requestEpoch !== requestEpochRef.current) return;
      setHistory(result.items);
      setPage(result.page);
      setHasNext(result.hasNext);
      setExpandedIndex(null);
      setHistoryViewState(result.items.length === 0 ? "empty" : "ready");
    } catch (error) {
      if (requestEpoch !== requestEpochRef.current) return;
      if (error instanceof ApiError && error.status === 401) {
        redirectToLogin();
        return;
      }
      if (error instanceof ApiError && error.status === 403) {
        denyAccess();
        return;
      }
      setHistory([]);
      setHasNext(false);
      setExpandedIndex(null);
      setHistoryViewState("error");
    } finally {
      if (requestEpoch === requestEpochRef.current) setIsRefreshing(false);
    }
  }, [appliedFilters, denyAccess, redirectToLogin]);

  function submitFilters(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const nextFilters: PosSettlementHistoryFilters = {
      partnerDisplayName: selectedPartnerName || undefined,
      search: searchInput.trim() || undefined,
    };
    setAppliedFilters(nextFilters);
    void loadHistory(0, false, nextFilters);
  }

  function resetFilters() {
    setSelectedPartnerName("");
    setSearchInput("");
    setAppliedFilters({});
    void loadHistory(0, false, {});
  }

  useEffect(() => {
    if (initialLoadStartedRef.current) return;
    initialLoadStartedRef.current = true;
    void loadHistory(0);
  }, [loadHistory]);

  if (accessState === "forbidden") {
    return <StatePanel title="접근 권한이 없습니다" description="이 계정으로는 결제 내역을 조회할 수 없습니다." />;
  }

  return (
    <main className={posSettlementFormStyles.page}>
      <section className={posSettlementFormStyles.container} aria-labelledby="pos-settlement-title">
        <header className={posSettlementFormStyles.header}>
          <div>
            <p className={posSettlementFormStyles.eyebrow}>TIEAT STORE</p>
            <h1 className={posSettlementFormStyles.title} id="pos-settlement-title">결제 내역</h1>
            <p className={posSettlementFormStyles.description}>
              POS에서 실제 결제한 뒤 저장한 기록을 날짜와 금액으로 다시 확인합니다.
            </p>
          </div>
          <button
            className={posSettlementFormStyles.refresh}
            disabled={isRefreshing || historyViewState === "loading"}
            onClick={() => void loadHistory(page, true)}
            type="button"
          >
            {isRefreshing ? "새로고침 중…" : "새로고침"}
          </button>
        </header>

        <section className={posSettlementFormStyles.historyCard} aria-labelledby="pos-settlement-history-title">
          <header className={posSettlementFormStyles.historyHeader}>
            <div>
              <h2 className={posSettlementFormStyles.historyTitle} id="pos-settlement-history-title">저장된 결제 기록</h2>
            </div>
            {historyViewState === "ready" || historyViewState === "empty" ? (
              hasActiveFilter ? <span className={posSettlementFormStyles.historyPageLabel}>필터 적용됨</span> : null
            ) : null}
          </header>

          <form className={posSettlementFormStyles.historyFilters} onSubmit={submitFilters}>
            <label className={posSettlementFormStyles.historyFilterField}>
              <span>협력사별 보기</span>
              <select
                aria-label="협력사별 보기"
                className={posSettlementFormStyles.historyFilterControl}
                onChange={(event) => setSelectedPartnerName(event.target.value)}
                value={selectedPartnerName}
              >
                <option value="">전체 협력사</option>
                {partnerOptions.map((partnerName) => <option key={partnerName} value={partnerName}>{partnerName}</option>)}
              </select>
            </label>
            <label className={posSettlementFormStyles.historyFilterField}>
              <span>협력사 검색</span>
              <input
                aria-label="협력사 검색"
                className={posSettlementFormStyles.historyFilterControl}
                onChange={(event) => setSearchInput(event.target.value)}
                placeholder="협력사명 입력"
                type="search"
                value={searchInput}
              />
            </label>
            <div className={posSettlementFormStyles.historyFilterActions}>
              <button className={posSettlementFormStyles.historyFilterSubmit} type="submit">조회</button>
              {(hasActiveFilter || selectedPartnerName || searchInput) ? (
                <button className={posSettlementFormStyles.historyFilterReset} onClick={resetFilters} type="button">초기화</button>
              ) : null}
            </div>
          </form>

          {historyViewState === "loading" ? (
            <div className={posSettlementFormStyles.state} aria-live="polite">
              <p className={posSettlementFormStyles.stateDescription}>결제 내역을 불러오는 중입니다.</p>
            </div>
          ) : historyViewState === "error" ? (
            <div className={posSettlementFormStyles.state}>
              <p className={posSettlementFormStyles.notice} role="alert">결제 내역을 불러오지 못했습니다.</p>
              <button
                className={posSettlementFormStyles.stateAction}
                disabled={isRefreshing}
                onClick={() => void loadHistory(page, true)}
                type="button"
              >
                {isRefreshing ? "내역 불러오는 중…" : "결제 내역 다시 불러오기"}
              </button>
            </div>
          ) : historyViewState === "empty" ? (
            <div className={posSettlementFormStyles.state}>
              <p className={posSettlementFormStyles.stateDescription}>
                {hasActiveFilter ? "조건에 맞는 결제 내역이 없습니다." : "저장된 결제 기록이 없습니다."}
              </p>
              {hasActiveFilter ? (
                <button className={posSettlementFormStyles.stateAction} onClick={resetFilters} type="button">필터 초기화</button>
              ) : null}
            </div>
          ) : (
            <ol className={posSettlementFormStyles.historyList} aria-label="결제 내역 목록">
              {history.map((record, index) => (
                <HistoryItem
                  index={index}
                  key={record.recordedAt + "-" + index}
                  onReceiptUploaded={(uploadedReceipt) => {
                    if (!record.posSettlementId) return;
                    setHistory((current) => current.map((candidate) => (
                      candidate.posSettlementId === record.posSettlementId
                        ? { ...candidate, receipt: receiptSummaryFromUpload(uploadedReceipt) }
                        : candidate
                    )));
                  }}
                  onToggle={() => setExpandedIndex((current) => (current === index ? null : index))}
                  record={record}
                  expanded={expandedIndex === index}
                />
              ))}
            </ol>
          )}

          {historyViewState === "ready" || historyViewState === "empty" ? (
            <StorePagination
              ariaLabel="결제 내역 페이지 이동"
              hasNext={hasNext}
              isLoading={isRefreshing}
              onPageChange={(nextPage) => void loadHistory(nextPage)}
              page={page}
            />
          ) : null}
        </section>
      </section>
    </main>
  );
}
