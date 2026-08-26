type RefreshButtonProps = {
  className?: string;
  disabled?: boolean;
  isRefreshing: boolean;
  onClick: () => void;
};

const baseClassName = "inline-flex h-11 shrink-0 items-center justify-center rounded-lg border border-[var(--border-strong)] bg-white px-4 text-sm font-semibold text-[var(--text-primary)] transition-colors hover:bg-[var(--surface)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--focus)] focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:text-[var(--text-muted)]";

export function RefreshButton({ className = "", disabled = false, isRefreshing, onClick }: RefreshButtonProps) {
  return (
    <button
      className={`${baseClassName} ${className}`.trim()}
      disabled={disabled}
      onClick={onClick}
      type="button"
    >
      {isRefreshing ? "새로고침 중…" : "새로고침"}
    </button>
  );
}
