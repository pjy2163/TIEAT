"use client";

type PaymentType = "POSTPAID" | "PREPAID_WITH_RECEIVABLE_OVERFLOW";
type PaymentSelection = PaymentType | "" | null;

const styles = {
  field: "space-y-2",
  label: "block text-sm font-semibold text-[var(--text-primary)]",
  hint: "text-xs leading-5 text-[var(--text-muted)]",
  inputRow: "relative",
  input: "block h-12 w-full rounded-lg border border-[var(--border-strong)] bg-white px-3 pr-10 text-base text-[var(--text-primary)] outline-none focus:border-[var(--accent)] focus:ring-2 focus:ring-[#dbe4ff] disabled:cursor-not-allowed disabled:bg-[var(--surface-subtle)] disabled:text-[var(--text-muted)]",
  currencySuffix: "pointer-events-none absolute inset-y-0 right-3 flex items-center text-sm text-[var(--text-secondary)]",
  choiceGrid: "grid gap-2 sm:grid-cols-2",
  choice: "flex min-h-12 cursor-pointer items-center gap-2 rounded-lg border border-[var(--border-strong)] px-3 text-sm text-[var(--text-primary)] has-[:checked]:border-[var(--accent)] has-[:checked]:bg-[#f4f6ff] has-[:checked]:font-semibold has-[:disabled]:cursor-not-allowed has-[:disabled]:opacity-60",
} as const;

export type StorePartnerPaymentFieldsProps = Readonly<{
  balanceHintId?: string;
  balanceInputId?: string;
  disabled?: boolean;
  initialPrepaidBalanceMinor: string;
  onInitialPrepaidBalanceMinorChange: (value: string) => void;
  onPaymentTypeChange: (value: PaymentType) => void;
  paymentType: PaymentSelection;
}>;

export function formatKoreanWonInput(value: string): string {
  if (value.length === 0) return "";
  if (!/^[\d,]*$/.test(value)) return value;
  const digits = value.replaceAll(",", "").replace(/^0+(?=\d)/, "");
  return digits.length === 0 ? "0" : Number(digits).toLocaleString("ko-KR");
}

export function StorePartnerPaymentFields({
  balanceHintId = "store-partner-initial-balance-hint",
  balanceInputId = "store-partner-initial-balance",
  disabled = false,
  initialPrepaidBalanceMinor,
  onInitialPrepaidBalanceMinorChange,
  onPaymentTypeChange,
  paymentType,
}: StorePartnerPaymentFieldsProps) {
  return (
    <>
      <fieldset className={styles.field} disabled={disabled}>
        <legend className={styles.label}>결제 유형</legend>
        <div className={styles.choiceGrid}>
          <label className={styles.choice}>
            <input checked={paymentType === "PREPAID_WITH_RECEIVABLE_OVERFLOW"} name="paymentType" onChange={() => onPaymentTypeChange("PREPAID_WITH_RECEIVABLE_OVERFLOW")} type="radio" />
            선불
          </label>
          <label className={styles.choice}>
            <input checked={paymentType === "POSTPAID"} name="paymentType" onChange={() => onPaymentTypeChange("POSTPAID")} type="radio" />
            후불
          </label>
        </div>
      </fieldset>

      {paymentType === "PREPAID_WITH_RECEIVABLE_OVERFLOW" ? (
        <div className={styles.field}>
          <label className={styles.label} htmlFor={balanceInputId}>초기 선불 잔액</label>
          <div className={styles.inputRow}>
            <input
              aria-describedby={balanceHintId}
              className={styles.input}
              disabled={disabled}
              id={balanceInputId}
              inputMode="numeric"
              onChange={(event) => onInitialPrepaidBalanceMinorChange(formatKoreanWonInput(event.target.value))}
              placeholder="0"
              value={initialPrepaidBalanceMinor}
            />
            <span aria-hidden="true" className={styles.currencySuffix}>원</span>
          </div>
          <p className={styles.hint} id={balanceHintId}>0 이상의 안전한 정수로 입력해 주세요. 예: 79,000원</p>
        </div>
      ) : null}
    </>
  );
}
