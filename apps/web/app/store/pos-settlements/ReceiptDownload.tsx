"use client";

import { useState } from "react";
import { ApiError } from "@/lib/store-api";
import { downloadPosSettlementReceipt } from "@/lib/pos-settlement-api";
import { posSettlementFormStyles } from "./PosSettlementForm.styles";

function receiptDownloadErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 404) {
    return "영수증을 찾지 못했거나 보관 기간이 지났습니다.";
  }
  return "영수증 다운로드 결과를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.";
}

export function ReceiptDownload({
  posSettlementId,
  fileName,
}: {
  posSettlementId: string;
  fileName?: string | null;
}) {
  const [isDownloading, setIsDownloading] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  async function download() {
    setIsDownloading(true);
    setMessage(null);
    try {
      const response = await downloadPosSettlementReceipt(posSettlementId);
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = fileName?.trim() || "receipt";
      document.body.append(anchor);
      anchor.click();
      anchor.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 0);
    } catch (error) {
      setMessage(receiptDownloadErrorMessage(error));
    } finally {
      setIsDownloading(false);
    }
  }

  return (
    <div className={posSettlementFormStyles.receiptDownload}>
      <button
        className={posSettlementFormStyles.stateAction}
        disabled={isDownloading}
        onClick={() => void download()}
        type="button"
      >
        {isDownloading ? "영수증 불러오는 중…" : "영수증 다운로드"}
      </button>
      {message ? <p className={posSettlementFormStyles.secondary} role="alert">{message}</p> : null}
    </div>
  );
}
