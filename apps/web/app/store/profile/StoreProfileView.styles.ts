const actionBase = "inline-flex items-center justify-center rounded-lg text-sm font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--focus)] disabled:cursor-not-allowed disabled:opacity-60";
const compactActionSize = "min-h-10 shrink-0 px-3";
const dialogActionSize = "min-h-11 px-4";
const ledgerAction = "border border-[#b9c8ff] bg-[#f4f6ff] text-[#244cda] hover:border-[#8ea8ff] hover:bg-[#e9eeff]";
const secondaryAction = "border border-[var(--border-strong)] bg-white text-[var(--text-primary)] hover:bg-[var(--surface-subtle)]";
const dangerAction = "border border-[#f1c7cd] bg-[#fff7f8] text-[var(--danger)] hover:bg-[#ffeef1]";
const primaryAction = "bg-[var(--accent)] text-white hover:bg-[#244cda] disabled:bg-[#98abef]";
const dangerConfirmAction = "bg-[var(--danger)] text-white hover:brightness-95";
const dialogAction = `${actionBase} ${dialogActionSize}`;
const dialogLedgerAction = `${dialogAction} ${ledgerAction}`;
const dialogNeutralAction = `${dialogAction} ${secondaryAction}`;

export const storeProfileStyles = {
  page: "min-h-dvh bg-[var(--surface-subtle)] px-5 py-6 sm:px-8 sm:py-10",
  container: "mx-auto w-full max-w-4xl",
  eyebrow: "text-xs font-semibold tracking-[0.08em] text-[var(--accent)]",
  title: "mt-2 text-2xl font-semibold tracking-[0.01em] text-[var(--text-primary)] sm:text-3xl",
  description: "mt-2 max-w-2xl text-sm leading-6 text-[var(--text-secondary)]",
  grid: "mt-8 grid gap-5 lg:grid-cols-[minmax(0,0.85fr)_minmax(0,1.15fr)]",
  card: "rounded-2xl border border-[var(--border)] bg-white p-5 shadow-[0_8px_30px_rgba(17,24,39,0.04)] sm:p-6",
  cardTitle: "text-base font-semibold text-[var(--text-primary)]",
  cardDescription: "mt-1 text-sm leading-5 text-[var(--text-secondary)]",
  kindFilter: "mt-4 h-10 w-full rounded-lg border border-[var(--border-strong)] bg-white px-3 text-sm font-medium text-[var(--text-primary)] outline-none focus:border-[var(--accent)] focus:ring-2 focus:ring-[#dbe4ff] disabled:cursor-not-allowed disabled:bg-[var(--surface-subtle)] disabled:text-[var(--text-muted)]",
  details: "mt-5 grid gap-4 text-sm",
  detailLabel: "text-xs font-semibold text-[var(--text-muted)]",
  detailValue: "mt-1 break-words font-medium text-[var(--text-primary)]",
  partnerList: "mt-5 divide-y divide-[var(--border)] rounded-xl border border-[var(--border)]",
  partnerItem: "flex min-w-0 flex-wrap items-center justify-between gap-4 px-4 py-4 first:rounded-t-xl last:rounded-b-xl",
  partnerMain: "min-w-0",
  partnerDetailTrigger: "flex min-h-10 w-full min-w-0 items-center justify-between gap-3 rounded-lg text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--focus)]",
  partnerName: "min-w-0 truncate text-sm font-semibold text-[var(--text-primary)]",
  partnerNameButton: "min-w-0 truncate rounded-md text-left text-sm font-semibold text-[var(--text-primary)] underline-offset-2 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--focus)]",
  kindBadge: "ml-2 inline-flex rounded-full bg-[var(--surface-subtle)] px-1.5 py-0.5 align-middle text-[10px] font-semibold text-[var(--text-muted)]",
  detailChevron: "shrink-0 text-xs font-semibold text-[var(--accent)]",
  partnerDetail: "mt-2 rounded-lg bg-[var(--surface-subtle)] px-3 py-3",
  partnerMeta: "mt-1 flex flex-wrap gap-x-3 gap-y-1 text-xs leading-5 text-[var(--text-secondary)]",
  partnerContacts: "mt-3 grid gap-1 text-xs leading-5 text-[var(--text-secondary)] sm:grid-cols-2 sm:gap-x-4",
  contactLabel: "inline font-semibold text-[var(--text-muted)] after:content-[':'] after:mr-1",
  partnerActions: "flex shrink-0 flex-wrap justify-end gap-2",
  actionBase,
  partnerLink: `${actionBase} ${compactActionSize} ${ledgerAction}`,
  detailButton: `${actionBase} ${compactActionSize} ${secondaryAction}`,
  deleteButton: `${actionBase} ${compactActionSize} ${dangerAction}`,
  addLink: "mt-5 inline-flex min-h-11 w-full items-center justify-center rounded-lg bg-[var(--accent)] px-4 text-sm font-semibold text-white hover:bg-[#244cda] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--focus)]",
  state: "mt-8 rounded-2xl border border-[var(--border)] bg-white p-6 text-center shadow-[0_8px_30px_rgba(17,24,39,0.04)]",
  stateTitle: "text-base font-semibold text-[var(--text-primary)]",
  stateDescription: "mx-auto mt-2 max-w-md text-sm leading-6 text-[var(--text-secondary)]",
  stateAction: "mt-5 inline-flex min-h-11 items-center justify-center rounded-lg border border-[var(--border-strong)] bg-white px-4 text-sm font-semibold text-[var(--text-primary)] hover:bg-[var(--surface-subtle)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--focus)]",
  notice: "mt-5 rounded-lg border border-[#cfdafe] bg-[#f4f6ff] px-3 py-3 text-sm leading-5 text-[#244cda]",
  error: "mt-5 rounded-lg border border-[#f1c7cd] bg-[#fff7f8] px-3 py-3 text-sm leading-5 text-[var(--danger)]",
  dialog: "fixed inset-0 z-50 m-0 flex min-h-full w-full items-center justify-center bg-black/35 p-5",
  dialogCard: "w-full max-w-md rounded-2xl bg-white p-6 shadow-[0_20px_60px_rgba(17,24,39,0.2)]",
  dialogTitle: "text-lg font-semibold text-[var(--text-primary)]",
  dialogName: "mt-4 break-words text-base font-semibold text-[var(--text-primary)]",
  dialogDescription: "mt-2 text-sm leading-6 text-[var(--text-secondary)]",
  dialogError: "mt-4 rounded-lg border border-[#f1c7cd] bg-[#fff7f8] px-3 py-3 text-sm leading-5 text-[var(--danger)]",
  dialogDetails: "mt-5 grid gap-3 text-sm leading-5 text-[var(--text-secondary)] [&_div]:flex [&_div]:items-baseline [&_div]:justify-between [&_dt]:font-semibold [&_dt]:text-[var(--text-muted)] [&_dd]:ml-4 [&_dd]:break-all [&_dd]:text-right [&_dd]:text-[var(--text-primary)]",
  fieldLabel: "mt-4 block text-xs font-semibold text-[var(--text-muted)] first:mt-5",
  fieldInput: "mt-2 h-11 w-full rounded-lg border border-[var(--border-strong)] bg-white px-3 text-sm text-[var(--text-primary)] outline-none focus:border-[var(--accent)] focus:ring-2 focus:ring-[#dbe4ff]",
  fieldHint: "mt-2 text-xs leading-5 text-[var(--text-muted)]",
  dialogActions: "mt-6 grid grid-cols-2 gap-2 sm:flex sm:flex-wrap sm:justify-end",
  dialogLedger: dialogLedgerAction,
  dialogSecondary: dialogNeutralAction,
  dialogDanger: `${dialogAction} ${dangerAction}`,
  dialogCancel: dialogNeutralAction,
  dialogConfirm: `${dialogAction} ${primaryAction}`,
  dialogDangerConfirm: `${dialogAction} ${dangerConfirmAction}`,
} as const;
