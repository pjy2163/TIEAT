"use client";

import { useEffect, useState } from "react";
import { ApiError } from "@/lib/store-api";
import {
  downloadPosSettlementReceipt,
  type PosSettlementReceiptContentType,
} from "@/lib/pos-settlement-api";
import { posSettlementFormStyles } from "./PosSettlementForm.styles";

function receiptPreviewErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 404) {
    return "영수증을 찾지 못했거나 보관 기간이 지났습니다.";
  }
  return "영수증 미리보기를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.";
}

export function ReceiptPreview({
  posSettlementId,
  fileName,
  contentType,
}: {
  posSettlementId: string;
  fileName?: string | null;
  contentType: PosSettlementReceiptContentType;
}) {
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    if (contentType === "application/pdf") return;
    let active = true;
    let objectUrl: string | null = null;

    void downloadPosSettlementReceipt(posSettlementId)
      .then(async (response) => {
        const blob = await response.blob();
        if (!active) return;
        objectUrl = URL.createObjectURL(blob);
        setPreviewUrl(objectUrl);
      })
      .catch((error: unknown) => {
        if (active) setMessage(receiptPreviewErrorMessage(error));
      });

    return () => {
      active = false;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [contentType, posSettlementId]);

  return (
    <div className={posSettlementFormStyles.receiptPreview} aria-label="영수증 미리보기">
      {contentType === "application/pdf" ? (
        <p className={posSettlementFormStyles.receiptPreviewMessage}>기존 PDF 영수증은 보안 정책상 제공하지 않습니다.</p>
      ) : previewUrl ? (
        <img
          alt={`첨부된 영수증 ${fileName ?? "이미지"}`}
          className={posSettlementFormStyles.receiptPreviewImage}
          src={previewUrl}
        />
      ) : message ? (
        <p className={posSettlementFormStyles.receiptPreviewMessage} role="alert">{message}</p>
      ) : (
        <p className={posSettlementFormStyles.receiptPreviewMessage} role="status">영수증 미리보기를 불러오는 중…</p>
      )}
    </div>
  );
}
