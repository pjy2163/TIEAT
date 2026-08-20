import { storeAuthStyles } from "../auth/StoreAuth.styles";

export const signupStyles = {
  step: "mt-5 flex items-center gap-2 text-xs font-medium text-[var(--text-muted)]",
  stepCurrent: "rounded-full bg-[#edf2ff] px-2.5 py-1 text-[var(--accent)]",
  stepPending: "rounded-full bg-[var(--surface-subtle)] px-2.5 py-1",
  searchRow: "flex flex-col gap-2 sm:flex-row",
  searchButton: "h-12 shrink-0 rounded-lg border border-[var(--border-strong)] px-4 text-sm font-semibold text-[var(--text-primary)] transition-colors hover:bg-[var(--surface-subtle)] disabled:cursor-not-allowed disabled:text-[var(--text-muted)]",
  resultList: "space-y-2 rounded-xl border border-[var(--border)] bg-[var(--surface)] p-2",
  result: "flex w-full items-start rounded-lg p-3 text-left transition-colors hover:bg-white aria-pressed:bg-[#edf2ff]",
  resultName: "min-w-0 text-sm font-semibold text-[var(--text-primary)]",
  resultMeta: "mt-0.5 text-xs leading-5 text-[var(--text-secondary)]",
  empty: "rounded-xl border border-dashed border-[var(--border-strong)] bg-[var(--surface)] px-3 py-4 text-sm leading-6 text-[var(--text-secondary)]",
  secondaryButton: "h-11 rounded-lg border border-[var(--border-strong)] px-4 text-sm font-semibold text-[var(--text-primary)] transition-colors hover:bg-[var(--surface-subtle)] disabled:cursor-not-allowed disabled:text-[var(--text-muted)]",
  success: "rounded-lg border border-[#c7e7d0] bg-[#f3fff6] px-3 py-3 text-sm leading-5 text-[#16713a]",
  actions: "flex flex-col-reverse gap-3 sm:flex-row sm:justify-between",
  button: `${storeAuthStyles.primaryButton} sm:w-auto sm:min-w-40`,
  backButton: "h-12 rounded-lg px-3 text-sm font-semibold text-[var(--text-secondary)] hover:bg-[var(--surface-subtle)]",
} as const;
