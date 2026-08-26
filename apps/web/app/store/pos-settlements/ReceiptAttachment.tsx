"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "@/lib/store-api";
import {
  uploadPosSettlementReceipt,
  type PosSettlementReceipt,
} from "@/lib/pos-settlement-api";
import { posSettlementFormStyles } from "./PosSettlementForm.styles";
import { ReceiptDownload } from "./ReceiptDownload";

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

function receiptValidationMessage(file: File): string | null {
  if (!(["image/jpeg", "image/png", "application/pdf"] as string[]).includes(file.type)) {
    return "JPG, PNG, PDF 파일만 첨부할 수 있습니다.";
  }
  if (file.size > 10 * 1024 * 1024) {
    return "영수증 파일은 10MiB 이하만 첨부할 수 있습니다.";
  }
  return null;
}

type ReceiptAttachmentProps = {
  onUploaded?: (receipt: PosSettlementReceipt) => void;
  onPendingFileChange?: (file: File | null) => void;
  pendingFile?: File | null;
  posSettlementId?: string;
};

export function ReceiptAttachment({
  onUploaded,
  onPendingFileChange,
  pendingFile = null,
  posSettlementId,
}: ReceiptAttachmentProps) {
  const [busy, setBusy] = useState<"upload" | null>(null);
  const [receipt, setReceipt] = useState<PosSettlementReceipt | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const pendingUploadAttemptRef = useRef<File | null>(null);

  const upload = useCallback(async (file: File) => {
    if (!posSettlementId) return;
    setBusy("upload");
    setMessage(null);
    try {
      const uploaded = await uploadPosSettlementReceipt(posSettlementId, file);
      setReceipt(uploaded);
      setMessage("영수증을 안전하게 첨부했습니다.");
      onUploaded?.(uploaded);
    } catch (error) {
      setMessage(receiptErrorMessage(error));
    } finally {
      setBusy(null);
    }
  }, [onUploaded, posSettlementId]);

  useEffect(() => {
    if (
      !posSettlementId
      || !pendingFile
      || receipt
      || busy !== null
      || pendingUploadAttemptRef.current === pendingFile
    ) return;
    pendingUploadAttemptRef.current = pendingFile;
    void upload(pendingFile);
  }, [busy, pendingFile, posSettlementId, receipt, upload]);

  function handleFileChange(file: File | undefined) {
    if (!file) return;
    const validationMessage = receiptValidationMessage(file);
    if (validationMessage) {
      onPendingFileChange?.(null);
      setMessage(validationMessage);
      return;
    }
    onPendingFileChange?.(file);
    setMessage(posSettlementId ? null : "결제 기록 저장 후 영수증을 자동으로 첨부합니다.");
    if (posSettlementId && !onPendingFileChange) void upload(file);
  }

  return (
    <section className={posSettlementFormStyles.receiptPanel}>
      <p className={posSettlementFormStyles.receiptTitle}>영수증</p>
      <p className={posSettlementFormStyles.receiptDescription}>JPG, PNG, PDF · 10MiB 이하</p>
      <div className={posSettlementFormStyles.receiptActions}>
        <label className={posSettlementFormStyles.receiptButton}>
          {busy === "upload" ? "영수증 첨부 중…" : "영수증 첨부"}
          <input
            accept="image/jpeg,image/png,application/pdf"
            capture="environment"
            disabled={busy !== null || receipt !== null}
            onChange={(event) => {
              handleFileChange(event.target.files?.[0]);
              event.currentTarget.value = "";
            }}
            type="file"
          />
        </label>
        {receipt && posSettlementId ? <ReceiptDownload posSettlementId={posSettlementId} fileName={receipt.fileName} /> : null}
      </div>
      {pendingFile ? <p className={posSettlementFormStyles.receiptFileName}>선택한 파일: {pendingFile.name}</p> : null}
      {message ? <p className={posSettlementFormStyles.receiptMessage} role="status">{message}</p> : null}
    </section>
  );
}
