import { storeActionStyles } from "../StoreAction.styles";

export const storeMealUsageQrViewStyles = {
  page: "min-h-dvh bg-[var(--surface-subtle)] px-5 py-6 sm:px-8 sm:py-10",
  container: "mx-auto w-full max-w-2xl",
  eyebrow: "text-xs font-semibold tracking-[0.08em] text-[var(--accent)]",
  title: "mt-2 break-words text-2xl font-semibold tracking-[0.01em] text-[var(--text-primary)] sm:text-3xl",
  description: "mt-2 max-w-xl text-sm leading-6 text-[var(--text-secondary)]",
  card: "mt-8 rounded-2xl border border-[var(--border)] bg-white p-5 shadow-[0_8px_30px_rgba(17,24,39,0.04)] sm:p-8",
  qrFrame: "mx-auto mt-6 flex min-h-[19rem] w-full max-w-[19rem] items-center justify-center rounded-xl border border-[var(--border)] bg-white p-4",
  qrImage: "block aspect-square h-auto w-full max-w-[17rem]",
  qrLoading: "text-center text-sm leading-6 text-[var(--text-secondary)]",
  expiry: "mt-5 text-center text-sm leading-6 text-[var(--text-secondary)]",
  backLink: `${storeActionStyles.neutral} mt-6`,
  state: "mt-8 rounded-2xl border border-[var(--border)] bg-white p-6 text-center shadow-[0_8px_30px_rgba(17,24,39,0.04)] sm:p-8",
  stateTitle: "text-lg font-semibold text-[var(--text-primary)]",
  stateDescription: "mx-auto mt-3 max-w-md text-sm leading-6 text-[var(--text-secondary)]",
  stateAction: `${storeActionStyles.neutral} mt-6`,
} as const;
