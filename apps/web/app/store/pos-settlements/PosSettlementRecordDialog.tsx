"use client";

import { type FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ApiError } from "@/lib/store-api";
import {
  getOutstandingReceivables,
  recordPosSettlement,
  type OutstandingReceivable,
  type PosSettlement,
} from "@/lib/pos-settlement-api";
import { posSettlementFormStyles } from "./PosSettlementForm.styles";
import { ReceiptAttachment } from "./ReceiptAttachment";

export type PosSettlementRecordSeed = {
  mealUsageIds: string[];
  mealContractId: string;
  partnerDisplayName: string | null;
};

type PosSettlementRecordDialogProps = {
  seed: PosSettlementRecordSeed;
  onAccessDenied: () => void;
  onClose: () => void;
  onSettlementRecorded: () => Promise<boolean>;
  onSessionExpired: () => void;
};

type ViewState = "loading" | "ready" | "error" | "success";

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

function normalizeAmountInput(value: string): string {
  return value.replace(/\D/g, "");
}

function formatAmountInput(value: string): string {
  return normalizeAmountInput(value).replace(/\B(?=(\d{3})+(?!\d))/g, ",");
}

function sameContractReceivables(
  seed: PosSettlementRecordSeed,
  items: OutstandingReceivable[],
): OutstandingReceivable[] | null {
  const scoped = items.filter((item) => item.mealContractId === seed.mealContractId);
  const selected = scoped.filter((item) => seed.mealUsageIds.includes(item.mealUsageId));
  const selectedIds = new Set(selected.map((item) => item.mealUsageId));
  return selected.length === seed.mealUsageIds.length
    && selectedIds.size === seed.mealUsageIds.length
    && selected.every((item) => item.receivableCreatedMinor > 0)
    ? scoped
    : null;
}

export function PosSettlementRecordDialog({
  onAccessDenied,
  onClose,
  onSettlementRecorded,
  onSessionExpired,
  seed,
}: PosSettlementRecordDialogProps) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const mountedRef = useRef(false);
  const requestEpochRef = useRef(0);
  const idempotencyKeyRef = useRef<string | null>(null);
  const submittingRef = useRef(false);
  const [viewState, setViewState] = useState<ViewState>("loading");
  const [receivables, setReceivables] = useState<OutstandingReceivable[]>([]);
  const [selectedUsageIds, setSelectedUsageIds] = useState<Set<string>>(new Set());
  const [posBusinessDate, setPosBusinessDate] = useState("");
  const [submittedTotalInput, setSubmittedTotalInput] = useState("");
  const [pendingReceiptFile, setPendingReceiptFile] = useState<File | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [settlement, setSettlement] = useState<PosSettlement | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [ledgerRefreshWarning, setLedgerRefreshWarning] = useState(false);

  const requestClose = useCallback(() => {
    if (submittingRef.current) return;
    const dialog = dialogRef.current;
    if (dialog?.open && typeof dialog.close === "function") {
      dialog.close();
      if (dialog.open) {
        dialog.removeAttribute("open");
        onClose();
      }
      return;
    }
    onClose();
  }, [onClose]);

  useEffect(() => {
    mountedRef.current = true;
    const dialog = dialogRef.current;
    if (!dialog) return () => undefined;

    const handleNativeClose = () => onClose();
    dialog.addEventListener("close", handleNativeClose);
    if (!dialog.open) {
      if (typeof dialog.showModal === "function") {
        try {
          dialog.showModal();
        } catch {
          dialog.setAttribute("open", "");
        }
      } else {
        dialog.setAttribute("open", "");
      }
    }
    requestAnimationFrame(() => closeButtonRef.current?.focus());

    return () => {
      mountedRef.current = false;
      requestEpochRef.current += 1;
      dialog.removeEventListener("close", handleNativeClose);
    };
  }, [onClose]);

  const loadReceivables = useCallback(async () => {
    const requestEpoch = requestEpochRef.current + 1;
    requestEpochRef.current = requestEpoch;
    setViewState("loading");
    setFormError(null);
    setSettlement(null);
    setSelectedUsageIds(new Set());
    setPendingReceiptFile(null);
    setLedgerRefreshWarning(false);
    if (seed.mealUsageIds.length === 0) {
      setViewState("error");
      setFormError("선택한 장부 항목의 계약 정보를 확인할 수 없습니다. 장부를 새로고침해 주세요.");
      return;
    }
    try {
      const overview = await getOutstandingReceivables();
      if (!mountedRef.current || requestEpochRef.current !== requestEpoch) return;
      const scoped = sameContractReceivables(seed, overview.items);
      if (!scoped) {
        setViewState("error");
        setFormError("선택한 결제할 금액이 현재 미수금 목록과 다릅니다. 장부를 새로고침해 주세요.");
        return;
      }
      const initial = scoped.filter((item) => seed.mealUsageIds.includes(item.mealUsageId));
      setReceivables(scoped);
      setSelectedUsageIds(new Set(initial.map((item) => item.mealUsageId)));
      setSubmittedTotalInput(String(initial.reduce((total, item) => total + item.receivableCreatedMinor, 0)));
      setViewState("ready");
    } catch (error) {
      if (!mountedRef.current || requestEpochRef.current !== requestEpoch) return;
      if (error instanceof ApiError && error.status === 401) {
        onSessionExpired();
        return;
      }
      if (error instanceof ApiError && error.status === 403) {
        onAccessDenied();
        return;
      }
      setViewState("error");
      setFormError("결제할 금액을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.");
    }
  }, [onAccessDenied, onSessionExpired, seed]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- Opening the dialog intentionally starts its request lifecycle.
    void loadReceivables();
  }, [loadReceivables]);

  const selectedReceivables = useMemo(
    () => receivables.filter((receivable) => selectedUsageIds.has(receivable.mealUsageId)),
    [receivables, selectedUsageIds],
  );
  const derivedTotalMinor = selectedReceivables.reduce(
    (total, receivable) => total + receivable.receivableCreatedMinor,
    0,
  );

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submittingRef.current) return;
    if (selectedReceivables.length === 0) {
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
    submittingRef.current = true;
    setIsSubmitting(true);
    setFormError(null);
    try {
      const result = await recordPosSettlement({
        mealContractId: seed.mealContractId,
        posBusinessDate,
        submittedTotalMinor,
        mealUsageIds: selectedReceivables.map((receivable) => receivable.mealUsageId),
      }, idempotencyKey);
      if (!mountedRef.current) return;
      setSettlement(result);
      setViewState("success");
      setLedgerRefreshWarning(false);
      try {
        const ledgerReloaded = await onSettlementRecorded();
        if (mountedRef.current) setLedgerRefreshWarning(!ledgerReloaded);
      } catch {
        if (mountedRef.current) setLedgerRefreshWarning(true);
      }
    } catch (error) {
      if (!mountedRef.current) return;
      if (error instanceof ApiError && error.status === 401) {
        onSessionExpired();
        return;
      }
      if (error instanceof ApiError && error.status === 403) {
        onAccessDenied();
        return;
      }
      setFormError(requestErrorMessage(error));
    } finally {
      submittingRef.current = false;
      if (mountedRef.current) setIsSubmitting(false);
    }
  }

  return (
    <dialog
      aria-labelledby="pos-settlement-record-dialog-title"
      aria-modal="true"
      className={posSettlementFormStyles.recordDialog}
      onKeyDown={(event) => {
        if (event.key !== "Escape") return;
        event.preventDefault();
        requestClose();
      }}
      onCancel={(event) => {
        event.preventDefault();
        requestClose();
      }}
      onClick={(event) => {
        if (event.target === event.currentTarget) requestClose();
      }}
      ref={dialogRef}
    >
      <div className={posSettlementFormStyles.recordDialogCard} onClick={(event) => event.stopPropagation()}>
        <header className={posSettlementFormStyles.recordDialogHeader}>
          <div>
            <h2 className={posSettlementFormStyles.recordDialogTitle} id="pos-settlement-record-dialog-title">
              결제 기록
            </h2>
            <p className={posSettlementFormStyles.recordDialogPartner}>{seed.partnerDisplayName ?? "협력사 정보 미입력"}</p>
          </div>
          <button
            className={posSettlementFormStyles.recordDialogClose}
            disabled={isSubmitting}
            onClick={requestClose}
            ref={closeButtonRef}
            type="button"
          >
            닫기
          </button>
        </header>

        <div className={posSettlementFormStyles.recordDialogBody}>
          {viewState === "loading" ? (
            <div className={posSettlementFormStyles.state} aria-live="polite">
              <h3 className={posSettlementFormStyles.stateTitle}>결제할 금액을 확인하는 중</h3>
              <p className={posSettlementFormStyles.stateDescription}>최신 미수금 목록을 불러오고 있습니다.</p>
            </div>
          ) : viewState === "error" ? (
            <div className={posSettlementFormStyles.state} role="alert">
              <h3 className={posSettlementFormStyles.stateTitle}>결제할 금액을 확인하지 못했습니다</h3>
              <p className={posSettlementFormStyles.stateDescription}>{formError}</p>
              <div className="mt-5 flex flex-wrap justify-center gap-2">
                <button className={posSettlementFormStyles.stateAction} onClick={() => void loadReceivables()} type="button">다시 시도</button>
                <button className={posSettlementFormStyles.stateAction} onClick={requestClose} type="button">닫기</button>
              </div>
            </div>
          ) : viewState === "success" && settlement ? (
            <div className={posSettlementFormStyles.success} role="status">
              <h3 className={posSettlementFormStyles.successTitle}>결제 기록 저장 완료</h3>
              <p className={posSettlementFormStyles.successDescription}>
                {seed.partnerDisplayName ?? "협력사 정보 미입력"} · 결제일 {settlement.posBusinessDate} · 결제 금액 {amountFormatter.format(settlement.submittedTotalMinor)}
              </p>
              {ledgerRefreshWarning ? (
                <p className={posSettlementFormStyles.recordDialogError} role="alert">
                  결제 기록은 저장됐지만 장부를 새로 불러오지 못했습니다. 현재 행 상태가 이전 값일 수 있습니다.
                </p>
              ) : null}
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
              {settlement.posSettlementId ? (
                <ReceiptAttachment
                  onPendingFileChange={setPendingReceiptFile}
                  pendingFile={pendingReceiptFile}
                  posSettlementId={settlement.posSettlementId}
                />
              ) : null}
              <button className={posSettlementFormStyles.newSettlement} onClick={requestClose} type="button">닫기</button>
            </div>
          ) : (
            <>
              <form className={posSettlementFormStyles.recordDialogForm} onSubmit={(event) => void submit(event)}>
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
                {formError ? <p className={posSettlementFormStyles.recordDialogError} role="alert">{formError}</p> : null}
                <div className={posSettlementFormStyles.fields}>
                  <label className={posSettlementFormStyles.label} htmlFor="pos-record-dialog-business-date">
                    결제일
                    <input
                      className={posSettlementFormStyles.input}
                      disabled={isSubmitting}
                      id="pos-record-dialog-business-date"
                      onChange={(event) => {
                        setPosBusinessDate(event.target.value);
                        idempotencyKeyRef.current = null;
                      }}
                      required
                      type="date"
                      value={posBusinessDate}
                    />
                  </label>
                  <label className={posSettlementFormStyles.label} htmlFor="pos-record-dialog-submitted-total">
                    결제 금액
                    <input
                      className={posSettlementFormStyles.input}
                      disabled={isSubmitting}
                      id="pos-record-dialog-submitted-total"
                      inputMode="numeric"
                      onChange={(event) => {
                        setSubmittedTotalInput(normalizeAmountInput(event.target.value));
                        idempotencyKeyRef.current = null;
                      }}
                      required
                      type="text"
                      value={formatAmountInput(submittedTotalInput)}
                    />
                  </label>
                </div>
                <ReceiptAttachment onPendingFileChange={setPendingReceiptFile} pendingFile={pendingReceiptFile} />
                <button className={posSettlementFormStyles.submit} disabled={isSubmitting || selectedReceivables.length === 0} type="submit">
                  {isSubmitting ? "결제 기록 저장 중…" : "결제 기록 저장하기"}
                </button>
              </form>
            </>
          )}
        </div>
      </div>
    </dialog>
  );
}
