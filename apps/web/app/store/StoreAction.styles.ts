const actionBase = "inline-flex items-center justify-center rounded-lg text-sm font-semibold leading-5 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--focus)] focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60";
const standardSize = "min-h-11 px-4";
const compactSize = "min-h-10 shrink-0 px-3";
const neutralSurface = "border border-[var(--border-strong)] bg-white text-[var(--text-primary)] hover:bg-[var(--surface-subtle)] disabled:text-[var(--text-muted)]";
const primarySurface = "bg-[var(--accent)] text-white hover:bg-[#244cda] disabled:bg-[#98abef]";
const ledgerSurface = "border border-[#b9c8ff] bg-[#f4f6ff] text-[#244cda] hover:border-[#8ea8ff] hover:bg-[#e9eeff]";
const dangerSurface = "border border-[#f1c7cd] bg-[#fff7f8] text-[var(--danger)] hover:bg-[#ffeef1]";
const dangerConfirmSurface = "bg-[var(--danger)] text-white hover:brightness-95";

export const storeActionStyles = {
  base: actionBase,
  standard: `${actionBase} ${standardSize}`,
  compact: `${actionBase} ${compactSize}`,
  neutral: `${actionBase} ${standardSize} ${neutralSurface}`,
  compactNeutral: `${actionBase} ${compactSize} ${neutralSurface}`,
  primary: `${actionBase} ${standardSize} ${primarySurface}`,
  ledger: `${actionBase} ${standardSize} ${ledgerSurface}`,
  compactLedger: `${actionBase} ${compactSize} ${ledgerSurface}`,
  danger: `${actionBase} ${standardSize} ${dangerSurface}`,
  compactDanger: `${actionBase} ${compactSize} ${dangerSurface}`,
  dangerConfirm: `${actionBase} ${standardSize} ${dangerConfirmSurface}`,
  neutralSurface,
  primarySurface,
  ledgerSurface,
} as const;
