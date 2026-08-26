"use client";

import { useState } from "react";
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

export function ReceiptAttachment({ posSettlementId }: { posSettlementId: string }) {
  const [busy, setBusy] = useState<"upload" | null>(null);
  const [receipt, setReceipt] = useState<PosSettlementReceipt | null>(null);
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
      const uploaded = await uploadPosSettlementReceipt(posSettlementId, file);
      setReceipt(uploaded);
      setMessage("영수증을 안전하게 첨부했습니다.");
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
          disabled={busy !== null || receipt !== null}
          onChange={(event) => void upload(event.target.files?.[0])}
          type="file"
        />
      </label>
      {receipt ? <ReceiptDownload posSettlementId={posSettlementId} fileName={receipt.fileName} /> : null}
      {message ? <p className={posSettlementFormStyles.secondary} role="status">{message}</p> : null}
    </div>
  );
}
