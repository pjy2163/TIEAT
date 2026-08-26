"use client";

import { type FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { ApiError } from "@/lib/store-api";
import {
  getOutstandingReceivables,
  getRecentPosSettlements,
  downloadPosSettlementReceipt,
  recordPosSettlement,
  takePosSettlementSelectionSeed,
  uploadPosSettlementReceipt,
  type OutstandingReceivable,
  type PartnerReceivableSummary,
  type PosSettlement,
  type PosSettlementSelectionSeed,
} from "@/lib/pos-settlement-api";
import { posSettlementFormStyles } from "./PosSettlementForm.styles";

type ReceivableViewState = "loading" | "ready" | "empty" | "error";
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

function requestErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 403 && error.errorCode === "CSRF_TOKEN_INVALID") {
    return "보안 확인이 만료되었습니다. 입력은 유지되며 다시 기록할 수 있습니다.";
  }
  if (error instanceof ApiError && error.errorCode === "POS_SETTLEMENT_TOTAL_MISMATCH") {
    return "결제 금액과 선택한 결제할 금액이 다릅니다.";
  }
  if (error instanceof ApiError && error.errorCode === "POS_SETTLEMENT_USAGE_ALREADY_ALLOCATED") {
    return "선택한 금액은 이미 다른 결제 기록에 포함되었습니다. 목록을 새로고침해 주세요.";
  }
  if (error instanceof ApiError && error.status === 409) {
    return "결제 기록이 현재 상태와 맞지 않습니다. 목록을 새로고침해 확인해 주세요.";
  }
  return "결제 기록 결과를 확인하지 못했습니다. 네트워크를 확인한 뒤 같은 입력으로 다시 시도해 주세요.";
}

function isPositiveInteger(value: string): boolean {
  if (value.length === 0 || value[0] === "0") return false;
  for (const character of value) {
    if (character < "0" || character > "9") return false;
  }
  return Number.isSafeInteger(Number(value)) && Number(value) > 0;
}

function seededSelectionError(): string {
  return "월별에서 선택한 금액이 현재 미수금 목록과 달라 기록할 수 없습니다. 목록을 새로고침한 뒤 다시 선택해 주세요.";
}

function receiptErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.errorCode === "POS_SETTLEMENT_RECEIPT_ALREADY_ATTACHED") {
    return "이 결제 기록에는 이미 영수증이 첨부되어 있습니다.";
  }
  if (error instanceof ApiError && error.errorCode === "POS_SETTLEMENT_RECEIPT_UNSAFE") {
    return "안전 확인을 통과하지 못한 파일은 첨부할 수 없습니다.";
  }
  if (error instanceof ApiError && error.status === 404) {
    return "영수증을 찾지 못했거나 보관 기간이 지났습니다.";
  }
  return "영수증 처리 결과를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.";
}

function ReceiptAttachment({ posSettlementId }: { posSettlementId: string }) {
  const [busy, setBusy] = useState<"upload" | "download" | null>(null);
  const [attached, setAttached] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  async function upload(file: File | undefined) {
    if (!file) return;
    if (!(["image/jpeg", "image/png", "application/pdf"] as string[]).includes(file.type)) {
      setMessage("JPG, PNG, PDF 파일만 첨부할 수 있습니다.");
      return;
    }
    if (file.size > 10 * 1024 * 1024) {
      setMessage("영수증 파일은 10MiB 이하만 첨부할 수 있습니다.");
      return;
    }
    setBusy("upload");
    setMessage(null);
    try {
      await uploadPosSettlementReceipt(posSettlementId, file);
      setAttached(true);
      setMessage("영수증을 안전하게 첨부했습니다.");
    } catch (error) {
      setMessage(receiptErrorMessage(error));
    } finally {
      setBusy(null);
    }
  }

  async function download() {
    setBusy("download");
    setMessage(null);
    try {
      const response = await downloadPosSettlementReceipt(posSettlementId);
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = "receipt";
      anchor.click();
      URL.revokeObjectURL(url);
      setAttached(true);
    } catch (error) {
      setMessage(receiptErrorMessage(error));
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className={posSettlementFormStyles.receiptActions}>
      <label className={posSettlementFormStyles.stateAction}>
        {busy === "upload" ? "영수증 첨부 중…" : "영수증 첨부"}
        <input
          accept="image/jpeg,image/png,application/pdf"
          capture="environment"
          disabled={busy !== null || attached}
          onChange={(event) => void upload(event.target.files?.[0])}
          type="file"
        />
      </label>
      <button
        className={posSettlementFormStyles.stateAction}
        disabled={busy !== null}
        onClick={() => void download()}
        type="button"
      >
        {busy === "download" ? "영수증 불러오는 중…" : "영수증 다운로드"}
      </button>
      {message ? <p className={posSettlementFormStyles.secondary} role="status">{message}</p> : null}
    </div>
  );
}

function reconstructSeedSelection(
  seed: PosSettlementSelectionSeed,
  items: OutstandingReceivable[],
): { selected: OutstandingReceivable[]; totalMinor: number } | null {
  const selected = items.filter((item) => seed.mealUsageIds.includes(item.mealUsageId));
  const selectedIds = new Set(selected.map((item) => item.mealUsageId));
  const contracts = new Set(selected.map((item) => item.mealContractId));
  if (selected.length !== seed.mealUsageIds.length
    || selectedIds.size !== seed.mealUsageIds.length
    || contracts.size !== 1
    || selected.some((item) => item.receivableCreatedMinor <= 0)) {
    return null;
  }
  const totalMinor = selected.reduce((total, item) => total + item.receivableCreatedMinor, 0);
  return Number.isSafeInteger(totalMinor) && totalMinor > 0 ? { selected, totalMinor } : null;
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

export function PosSettlementForm() {
  const router = useRouter();
  const [accessState, setAccessState] = useState<AccessState>("allowed");
  const [receivableViewState, setReceivableViewState] = useState<ReceivableViewState>("loading");
  const [historyViewState, setHistoryViewState] = useState<HistoryViewState>("loading");
  const [receivables, setReceivables] = useState<OutstandingReceivable[]>([]);
  const [partnerSummaries, setPartnerSummaries] = useState<PartnerReceivableSummary[]>([]);
  const [history, setHistory] = useState<PosSettlement[]>([]);
  const [selectedUsageIds, setSelectedUsageIds] = useState<Set<string>>(new Set());
  const [posBusinessDate, setPosBusinessDate] = useState("");
  const [submittedTotalInput, setSubmittedTotalInput] = useState("");
  const [formError, setFormError] = useState<string | null>(null);
  const [isReceivableRefreshing, setIsReceivableRefreshing] = useState(false);
  const [isHistoryRefreshing, setIsHistoryRefreshing] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [settlement, setSettlement] = useState<PosSettlement | null>(null);
  const [expandedSettlementId, setExpandedSettlementId] = useState<string | null>(null);
  const idempotencyKeyRef = useRef<string | null>(null);
  const initialLoadStartedRef = useRef(false);
  const requestEpochRef = useRef(0);
  const selectedUsageIdsRef = useRef<Set<string>>(new Set());
  const pendingSelectionSeedRef = useRef<PosSettlementSelectionSeed | null>(null);

  const resetIdempotencyKey = useCallback(() => {
    idempotencyKeyRef.current = null;
  }, []);

  const replaceSelectedUsageIds = useCallback((next: Set<string>) => {
    selectedUsageIdsRef.current = next;
    setSelectedUsageIds(next);
  }, []);

  const clearSensitiveState = useCallback(() => {
    setReceivables([]);
    setPartnerSummaries([]);
    setHistory([]);
    replaceSelectedUsageIds(new Set());
    setSettlement(null);
    setExpandedSettlementId(null);
    setPosBusinessDate("");
    setSubmittedTotalInput("");
    setFormError(null);
    resetIdempotencyKey();
  }, [replaceSelectedUsageIds, resetIdempotencyKey]);

  const redirectToLogin = useCallback(() => {
    requestEpochRef.current += 1;
    clearSensitiveState();
    setReceivableViewState("loading");
    setHistoryViewState("loading");
    router.replace("/store/login?next=/store/pos-settlements");
  }, [clearSensitiveState, router]);

  const denyAccess = useCallback(() => {
    requestEpochRef.current += 1;
    clearSensitiveState();
    setAccessState("forbidden");
  }, [clearSensitiveState]);

  const loadReceivables = useCallback(async (manual = false) => {
    const requestEpoch = requestEpochRef.current;
    if (manual) {
      setIsReceivableRefreshing(true);
      setFormError(null);
    } else {
      setReceivableViewState("loading");
    }
    try {
      const overview = await getOutstandingReceivables();
      if (requestEpoch !== requestEpochRef.current) return;
      setReceivables(overview.items);
      setPartnerSummaries(overview.partners);
      const pendingSeed = pendingSelectionSeedRef.current;
      pendingSelectionSeedRef.current = null;
      const reconstructed = pendingSeed ? reconstructSeedSelection(pendingSeed, overview.items) : null;
      replaceSelectedUsageIds(reconstructed ? new Set(reconstructed.selected.map((item) => item.mealUsageId)) : new Set());
      resetIdempotencyKey();
      if (pendingSeed && reconstructed) {
        setSubmittedTotalInput(String(reconstructed.totalMinor));
        setFormError(null);
      } else if (pendingSeed) {
        setSubmittedTotalInput("");
        setFormError(seededSelectionError());
      }
      setReceivableViewState(overview.partners.length === 0 ? "empty" : "ready");
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
      setReceivableViewState("error");
    } finally {
      if (requestEpoch === requestEpochRef.current) {
        setIsReceivableRefreshing(false);
      }
    }
  }, [denyAccess, redirectToLogin, replaceSelectedUsageIds, resetIdempotencyKey]);

  const loadHistory = useCallback(async (manual = false) => {
    const requestEpoch = requestEpochRef.current;
    if (manual) {
      setIsHistoryRefreshing(true);
    } else {
      setHistoryViewState("loading");
    }
    try {
      const nextHistory = await getRecentPosSettlements();
      if (requestEpoch !== requestEpochRef.current) return;
      setHistory(nextHistory.items);
      setHistoryViewState(nextHistory.items.length === 0 ? "empty" : "ready");
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
      setHistoryViewState("error");
    } finally {
      if (requestEpoch === requestEpochRef.current) {
        setIsHistoryRefreshing(false);
      }
    }
  }, [denyAccess, redirectToLogin]);

  useEffect(() => {
    if (initialLoadStartedRef.current) return;
    initialLoadStartedRef.current = true;
    pendingSelectionSeedRef.current = takePosSettlementSelectionSeed();
    void loadReceivables();
    void loadHistory();
  }, [loadHistory, loadReceivables]);

  const selectedReceivables = useMemo(
    () => receivables.filter((receivable) => selectedUsageIds.has(receivable.mealUsageId)),
    [receivables, selectedUsageIds],
  );
  const receivablesByMealContractId = useMemo(() => {
    const grouped = new Map<string, OutstandingReceivable[]>();
    for (const receivable of receivables) {
      const items = grouped.get(receivable.mealContractId) ?? [];
      items.push(receivable);
      grouped.set(receivable.mealContractId, items);
    }
    return grouped;
  }, [receivables]);
  const selectedMealContractId = selectedReceivables[0]?.mealContractId ?? null;
  const derivedTotalMinor = selectedReceivables.reduce(
    (total, receivable) => total + receivable.receivableCreatedMinor,
    0,
  );

  const changeReceivableSelection = useCallback((
    receivable: OutstandingReceivable,
    shouldSelect: boolean,
  ): boolean => {
    const next = new Set(selectedUsageIdsRef.current);
    if (shouldSelect) {
      const currentContractId = receivables.find((item) => next.has(item.mealUsageId))?.mealContractId;
      if (currentContractId && currentContractId !== receivable.mealContractId) {
        return false;
      }
      if (next.has(receivable.mealUsageId)) return false;
      next.add(receivable.mealUsageId);
    } else {
      if (!next.delete(receivable.mealUsageId)) return false;
    }
    replaceSelectedUsageIds(next);
    return true;
  }, [receivables, replaceSelectedUsageIds]);

  function toggleReceivable(receivable: OutstandingReceivable) {
    if (isSubmitting) return;
    const changed = changeReceivableSelection(
      receivable,
      !selectedUsageIdsRef.current.has(receivable.mealUsageId),
    );
    if (!changed) return;
    resetIdempotencyKey();
    setFormError(null);
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (isSubmitting) return;
    if (!selectedMealContractId || selectedReceivables.length === 0) {
      setFormError("결제 기록에 포함할 결제할 금액을 하나 이상 선택해 주세요.");
      return;
    }
    if (!posBusinessDate) {
      setFormError("결제일을 입력해 주세요.");
      return;
    }
    if (!isPositiveInteger(submittedTotalInput)) {
      setFormError("결제 금액은 1원 이상의 정수로 입력해 주세요.");
      return;
    }
    const submittedTotalMinor = Number(submittedTotalInput);
    if (submittedTotalMinor !== derivedTotalMinor) {
      setFormError("결제 금액과 선택한 결제할 금액이 다릅니다.");
      return;
    }
    const idempotencyKey = idempotencyKeyRef.current ?? globalThis.crypto?.randomUUID?.();
    if (!idempotencyKey) {
      setFormError("이 브라우저에서는 안전한 재시도 키를 만들 수 없습니다.");
      return;
    }
    idempotencyKeyRef.current = idempotencyKey;
    setIsSubmitting(true);
    setFormError(null);
    try {
      const result = await recordPosSettlement({
        mealContractId: selectedMealContractId,
        posBusinessDate,
        submittedTotalMinor,
        mealUsageIds: selectedReceivables.map((receivable) => receivable.mealUsageId),
      }, idempotencyKey);
      setSettlement(result);
      void loadHistory();
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        redirectToLogin();
        return;
      }
      if (error instanceof ApiError && error.status === 403) {
        denyAccess();
        return;
      }
      setFormError(requestErrorMessage(error));
    } finally {
      setIsSubmitting(false);
    }
  }

  function startAnotherSettlement() {
    setSettlement(null);
    replaceSelectedUsageIds(new Set());
    setPosBusinessDate("");
    setSubmittedTotalInput("");
    setFormError(null);
    resetIdempotencyKey();
    void loadReceivables();
  }

  function refreshAll() {
    void loadReceivables(true);
    void loadHistory(true);
  }

  if (accessState === "forbidden") {
    return <StatePanel title="접근 권한이 없습니다" description="이 계정으로는 결제할 금액을 조회하거나 결제 기록을 저장할 수 없습니다." />;
  }

  return (
    <main className={posSettlementFormStyles.page}>
      <section className={posSettlementFormStyles.container} aria-labelledby="pos-settlement-title">
        <header className={posSettlementFormStyles.header}>
          <div>
            <p className={posSettlementFormStyles.eyebrow}>TIEAT STORE</p>
            <h1 className={posSettlementFormStyles.title} id="pos-settlement-title">결제할 금액</h1>
            <p className={posSettlementFormStyles.description}>
              월별 장부와 별도로 모든 달의 남은 금액을 보여줍니다. 실제 결제는 POS에서 하고, 여기에는 결제 기록만 저장합니다.
            </p>
          </div>
          <button
            className={posSettlementFormStyles.refresh}
            disabled={isReceivableRefreshing || isHistoryRefreshing || isSubmitting}
            onClick={refreshAll}
            type="button"
          >
            {isReceivableRefreshing || isHistoryRefreshing ? "새로고침 중…" : "새로고침"}
          </button>
        </header>

        <section className={posSettlementFormStyles.card} aria-labelledby="pos-settlement-record-title">
          {settlement ? (
            <div className={posSettlementFormStyles.success} role="status">
              <h2 className="sr-only" id="pos-settlement-record-title">결제 기록 저장 완료</h2>
              <p className={posSettlementFormStyles.successDescription}>
                결제일 {settlement.posBusinessDate} · 결제 금액 {amountFormatter.format(settlement.submittedTotalMinor)} · 기록 시각 {dateFormatter.format(new Date(settlement.recordedAt))}
              </p>
              <ul className={posSettlementFormStyles.allocationList} aria-label="결제 기록에 포함된 금액">
                {settlement.allocations.map((allocation, index) => (
                  <li key={index}>
                    <span>
                      {allocation.partnerDisplayName ?? "협력사 정보 없음"}
                      <span className={posSettlementFormStyles.secondary}>
                        {dateFormatter.format(new Date(allocation.confirmedAt))}
                      </span>
                    </span>
                    <strong>{amountFormatter.format(allocation.receivableAmountMinor)}</strong>
                  </li>
                ))}
              </ul>
              {settlement.posSettlementId ? <ReceiptAttachment posSettlementId={settlement.posSettlementId} /> : null}
              <button className={posSettlementFormStyles.newSettlement} onClick={startAnotherSettlement} type="button">다른 결제 기록</button>
            </div>
          ) : receivableViewState === "loading" ? (
            <div className={posSettlementFormStyles.state}>
              <h2 className={posSettlementFormStyles.stateTitle} id="pos-settlement-record-title">결제할 금액을 불러오는 중</h2>
              <p className={posSettlementFormStyles.stateDescription}>잠시만 기다려 주세요.</p>
            </div>
          ) : receivableViewState === "error" ? (
            <div className={posSettlementFormStyles.state}>
              <h2 className={posSettlementFormStyles.stateTitle} id="pos-settlement-record-title">결제할 금액을 불러오지 못했습니다</h2>
              <p className={posSettlementFormStyles.stateDescription}>네트워크 상태를 확인한 뒤 다시 시도해 주세요.</p>
              <button className={posSettlementFormStyles.stateAction} onClick={() => void loadReceivables()} type="button">결제할 금액 다시 불러오기</button>
            </div>
          ) : receivableViewState === "empty" ? (
            <div className={posSettlementFormStyles.state}>
              <h2 className={posSettlementFormStyles.stateTitle} id="pos-settlement-record-title">결제할 금액 없음</h2>
              <p className={posSettlementFormStyles.stateDescription}>현재 남아 있는 결제할 금액이 없습니다.</p>
            </div>
          ) : (
            <>
              <h2 className="sr-only" id="pos-settlement-record-title">결제할 금액</h2>
              {selectedMealContractId ? (
                <p className={posSettlementFormStyles.selectionHint} role="status">같은 협력사의 결제할 금액만 한 번에 기록할 수 있습니다.</p>
              ) : null}
              {formError ? <p className={posSettlementFormStyles.notice} role="alert">{formError}</p> : null}
              <div className={posSettlementFormStyles.partnerList}>
                {partnerSummaries.map((partner) => {
                  const partnerReceivables = receivablesByMealContractId.get(partner.mealContractId) ?? [];
                  const partnerName = partner.partnerDisplayName ?? "협력사 정보 없음";
                  return (
                    <section className={posSettlementFormStyles.partnerSection} key={partner.mealContractId}>
                      <header className={posSettlementFormStyles.partnerHeader}>
                        <div>
                          <h3 className={posSettlementFormStyles.partnerTitle}>{partnerName}</h3>
                          <p className={posSettlementFormStyles.partnerMeta}>
                            마지막 결제일: {partner.previousPosBusinessDate ?? "기록 없음"}
                          </p>
                        </div>
                        <dl className={posSettlementFormStyles.partnerSummary}>
                          <div>
                            <dt>마지막 결제일 이후 사용 금액</dt>
                            <dd>{amountFormatter.format(partner.periodConfirmedUsageTotalMinor)}</dd>
                          </div>
                          <div>
                            <dt>선불로 처리된 금액</dt>
                            <dd>{amountFormatter.format(partner.periodPrepaidAppliedTotalMinor)}</dd>
                          </div>
                          <div>
                            <dt>결제할 금액</dt>
                            <dd>{partner.outstandingReceivableCount}건 · {amountFormatter.format(partner.outstandingReceivableTotalMinor)}</dd>
                          </div>
                        </dl>
                      </header>
                      <span className="sr-only">선불 잔액으로 처리되어 추가 결제할 금액 없음</span>
                      {partnerReceivables.length === 0 ? (
                        <p className={posSettlementFormStyles.noPartnerReceivables}>결제할 금액 없음</p>
                      ) : (
                        <div className={posSettlementFormStyles.tableWrap}>
                          <table className={posSettlementFormStyles.table}>
                            <thead className={posSettlementFormStyles.tableHeader}>
                              <tr>
                                <th scope="col">선택</th>
                                <th scope="col">사용한 날짜</th>
                                <th scope="col" className="text-right">남은 금액</th>
                              </tr>
                            </thead>
                            <tbody>
                              {partnerReceivables.map((receivable) => {
                                const disabled = isSubmitting || Boolean(
                                  selectedMealContractId && selectedMealContractId !== receivable.mealContractId,
                                );
                                const checked = selectedUsageIds.has(receivable.mealUsageId);
                                return (
                                  <tr
                                    className={disabled ? posSettlementFormStyles.disabledRow : posSettlementFormStyles.row}
                                    key={receivable.mealUsageId}
                                  >
                                    <td>
                                      <input
                                        aria-label={
                                          (receivable.partnerDisplayName ?? partnerName)
                                          + " "
                                          + dateFormatter.format(new Date(receivable.confirmedAt))
                                          + " 선택"
                                        }
                                        checked={checked}
                                        className={posSettlementFormStyles.checkbox}
                                        disabled={disabled}
                                        onChange={() => toggleReceivable(receivable)}
                                        type="checkbox"
                                      />
                                    </td>
                                    <td>
                                      <p className={posSettlementFormStyles.primary}>
                                        {dateFormatter.format(new Date(receivable.confirmedAt))}
                                      </p>
                                    </td>
                                    <td className={posSettlementFormStyles.amount}>
                                      {amountFormatter.format(receivable.receivableCreatedMinor)}
                                    </td>
                                  </tr>
                                );
                              })}
                            </tbody>
                          </table>
                        </div>
                      )}
                    </section>
                  );
                })}
              </div>

              {receivables.length > 0 ? (
                <form className={posSettlementFormStyles.form} onSubmit={(event) => void submit(event)}>
                  <h2 className={posSettlementFormStyles.formTitle}>선택한 결제할 금액 기록</h2>
                  <p className={posSettlementFormStyles.formDescription}>선택한 남은 금액과 결제 금액이 같을 때만 기록합니다.</p>
                  <dl className={posSettlementFormStyles.totals}>
                    <div>
                      <dt>선택한 결제할 금액</dt>
                      <dd>{amountFormatter.format(derivedTotalMinor)}</dd>
                    </div>
                    <div>
                      <dt>선택한 항목 수</dt>
                      <dd>{selectedReceivables.length}건</dd>
                    </div>
                  </dl>
                  <div className={posSettlementFormStyles.fields}>
                    <label className={posSettlementFormStyles.label} htmlFor="pos-business-date">
                      결제일
                      <input
                        className={posSettlementFormStyles.input}
                        disabled={isSubmitting}
                        id="pos-business-date"
                        onChange={(event) => {
                          setPosBusinessDate(event.target.value);
                          resetIdempotencyKey();
                        }}
                        required
                        type="date"
                        value={posBusinessDate}
                      />
                    </label>
                    <label className={posSettlementFormStyles.label} htmlFor="pos-submitted-total">
                      결제 금액
                      <input
                        className={posSettlementFormStyles.input}
                        disabled={isSubmitting}
                        id="pos-submitted-total"
                        inputMode="numeric"
                        min="1"
                        onChange={(event) => {
                          setSubmittedTotalInput(event.target.value);
                          resetIdempotencyKey();
                        }}
                        required
                        step="1"
                        type="number"
                        value={submittedTotalInput}
                      />
                    </label>
                  </div>
                  <button className={posSettlementFormStyles.submit} disabled={isSubmitting || selectedReceivables.length === 0} type="submit">
                    {isSubmitting ? "선택한 결제할 금액 기록 중…" : "선택한 결제할 금액 기록하기"}
                  </button>
                </form>
              ) : null}
            </>
          )}
        </section>

        <section className={posSettlementFormStyles.historyCard} aria-labelledby="pos-settlement-history-title">
          <header className={posSettlementFormStyles.historyHeader}>
            <div>
              <h2 className={posSettlementFormStyles.historyTitle} id="pos-settlement-history-title">최근 결제 기록</h2>
            </div>
            {historyViewState === "ready" || historyViewState === "empty" ? (
              <button
                className={posSettlementFormStyles.stateAction}
                disabled={isHistoryRefreshing}
                onClick={() => void loadHistory(true)}
                type="button"
              >
                {isHistoryRefreshing ? "결제 기록 불러오는 중…" : "결제 기록 다시 불러오기"}
              </button>
            ) : null}
          </header>

          {historyViewState === "loading" ? (
            <div className={posSettlementFormStyles.state}>
              <p className={posSettlementFormStyles.stateDescription}>최근 결제 기록을 불러오는 중입니다.</p>
            </div>
          ) : historyViewState === "error" ? (
            <div className={posSettlementFormStyles.state}>
              <p className={posSettlementFormStyles.notice} role="alert">최근 결제 기록을 불러오지 못했습니다.</p>
              <button
                className={posSettlementFormStyles.stateAction}
                disabled={isHistoryRefreshing}
                onClick={() => void loadHistory(true)}
                type="button"
              >
                {isHistoryRefreshing ? "결제 기록 불러오는 중…" : "결제 기록 다시 불러오기"}
              </button>
            </div>
          ) : historyViewState === "empty" ? (
            <div className={posSettlementFormStyles.state}>
              <p className={posSettlementFormStyles.stateDescription}>최근 결제 기록이 없습니다.</p>
            </div>
          ) : (
            <ol className={posSettlementFormStyles.historyList} aria-label="최근 결제 기록 목록">
              {history.map((record, index) => {
                const historyKey = index + "-" + record.recordedAt;
                const isExpanded = expandedSettlementId === historyKey;
                const detailId = "pos-settlement-detail-" + index;
                const detailLabel = "결제일 " + record.posBusinessDate + (isExpanded ? " 상세 닫기" : " 상세 보기");
                return (
                  <li className={posSettlementFormStyles.historyItem} key={historyKey}>
                    <article>
                      <button
                        aria-controls={detailId}
                        aria-expanded={isExpanded}
                        aria-label={detailLabel}
                        className={posSettlementFormStyles.historyToggle}
                        onClick={() => setExpandedSettlementId((current) => (
                          current === historyKey ? null : historyKey
                        ))}
                        type="button"
                      >
                        <h3 className={posSettlementFormStyles.historyItemTitle}>결제일 {record.posBusinessDate}</h3>
                        <p className={posSettlementFormStyles.historyItemMeta}>
                          결제 금액 {amountFormatter.format(record.submittedTotalMinor)} · 상세 {isExpanded ? "닫기" : "보기"}
                        </p>
                      </button>
                      {isExpanded ? (
                        <div className={posSettlementFormStyles.historyDetail} id={detailId}>
                          <dl className={posSettlementFormStyles.historyDetailGrid}>
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
                          {record.posSettlementId ? <ReceiptAttachment posSettlementId={record.posSettlementId} /> : null}
                        </div>
                      ) : null}
                    </article>
                  </li>
                );
              })}
            </ol>
          )}
        </section>
      </section>
    </main>
  );
}
