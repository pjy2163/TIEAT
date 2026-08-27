import Link from "next/link";
import { partnerKindLabel } from "@/lib/partner-kind";
import type { StorePartner } from "@/lib/store-partner-api";
import { storeProfileStyles as styles } from "./StoreProfileView.styles";
import { ledgerHref, formatAmount, paymentLabel } from "./StoreProfileView.helpers";
import type { StoreProfileModalMode } from "./StoreProfileView.types";

type StorePartnerDetailDialogProps = Readonly<{
  activePartner: StorePartner;
  modalMode: StoreProfileModalMode;
  paymentType: StorePartner["paymentType"];
  prepaidBalance: string;
  paymentError: string | null;
  isSavingPayment: boolean;
  onOpenPaymentEditor: () => void;
  onOpenArchiveWarning: () => void;
  onClose: () => void;
  onPaymentCancel: () => void;
  onPaymentTypeChange: (paymentType: StorePartner["paymentType"]) => void;
  onPrepaidBalanceChange: (value: string) => void;
  onSavePaymentType: () => void;
}>;

export function StorePartnerDetailDialog({
  activePartner,
  modalMode,
  paymentType,
  prepaidBalance,
  paymentError,
  isSavingPayment,
  onOpenPaymentEditor,
  onOpenArchiveWarning,
  onClose,
  onPaymentCancel,
  onPaymentTypeChange,
  onPrepaidBalanceChange,
  onSavePaymentType,
}: StorePartnerDetailDialogProps) {
  if (modalMode === "detail") {
    return (
      <>
        <h2 className={styles.dialogTitle} id="store-partner-dialog-title">협력사 상세</h2>
        <p className={styles.dialogName}>{activePartner.partnerDisplayName}</p>
        <dl className={styles.dialogDetails}>
          <div><dt>협력사 유형</dt><dd>{partnerKindLabel(activePartner.partnerKind)}</dd></div>
          <div><dt>대표자 전화번호</dt><dd>{activePartner.representativePhone ?? "미등록"}</dd></div>
          <div><dt>대표자 이메일</dt><dd>{activePartner.representativeEmail ?? "미등록"}</dd></div>
          <div><dt>현재 결제 유형</dt><dd>{paymentLabel(activePartner.paymentType)}</dd></div>
        </dl>
        <div className={styles.dialogActions}>
          <Link className={styles.dialogLedger} href={ledgerHref(activePartner.mealContractId)}>장부 보기</Link>
          <button className={styles.dialogLedger} disabled={isSavingPayment} onClick={onOpenPaymentEditor} type="button">결제 유형 변경</button>
          <button className={styles.dialogDanger} onClick={onOpenArchiveWarning} type="button">협력사 삭제</button>
          <button className={styles.dialogCancel} onClick={onClose} type="button">닫기</button>
        </div>
      </>
    );
  }

  if (modalMode !== "payment") return null;

  return (
    <>
      <h2 className={styles.dialogTitle} id="store-partner-dialog-title">결제 유형 변경</h2>
      <p className={styles.dialogName}>{activePartner.partnerDisplayName}</p>
      <p className={styles.dialogDescription}>현재 결제 유형: {paymentLabel(activePartner.paymentType)}</p>
      <label className={styles.fieldLabel} htmlFor="partner-payment-type">변경할 결제 유형</label>
      <select
        className={styles.fieldInput}
        id="partner-payment-type"
        onChange={(event) => onPaymentTypeChange(event.target.value as StorePartner["paymentType"])}
        value={paymentType}
      >
        <option value="POSTPAID">후불</option>
        <option value="PREPAID_WITH_RECEIVABLE_OVERFLOW">선불</option>
      </select>
      {paymentType === "PREPAID_WITH_RECEIVABLE_OVERFLOW" ? (
        <>
          <label className={styles.fieldLabel} htmlFor="partner-prepaid-balance">전환 시 선불 잔액</label>
          <input
            autoFocus
            className={styles.fieldInput}
            id="partner-prepaid-balance"
            inputMode="numeric"
            onChange={(event) => onPrepaidBalanceChange(formatAmount(event.target.value))}
            placeholder="1,000"
            value={prepaidBalance}
          />
          <p className={styles.fieldHint}>1원 이상 입력해 주세요.</p>
        </>
      ) : null}
      {paymentError ? <p className={styles.dialogError} role="alert">{paymentError}</p> : null}
      <div className={styles.dialogActions}>
        <button className={styles.dialogCancel} onClick={onPaymentCancel} type="button">취소</button>
        <button className={styles.dialogConfirm} disabled={isSavingPayment} onClick={onSavePaymentType} type="button">
          {isSavingPayment ? "변경 중..." : "변경 저장"}
        </button>
      </div>
    </>
  );
}
