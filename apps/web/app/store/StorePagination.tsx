import { storeActionStyles } from "./StoreAction.styles";

type StorePaginationProps = Readonly<{
  ariaLabel: string;
  hasNext: boolean;
  isLoading?: boolean;
  onPageChange: (page: number) => void;
  page: number;
}>;

const styles = {
  nav: "flex items-center justify-center gap-3 border-t border-[var(--border)] px-5 py-4 sm:px-6",
  button: `${storeActionStyles.base} h-11 min-w-20 ${storeActionStyles.neutralSurface} whitespace-nowrap px-3`,
  pageLabel: "min-w-16 text-center text-sm font-medium tabular-nums text-[var(--text-secondary)]",
} as const;

export function StorePagination({
  ariaLabel,
  hasNext,
  isLoading = false,
  onPageChange,
  page,
}: StorePaginationProps) {
  return (
    <nav aria-label={ariaLabel} className={styles.nav}>
      <button
        className={styles.button}
        disabled={isLoading || page === 0}
        onClick={() => onPageChange(page - 1)}
        type="button"
      >
        이전
      </button>
      <span className={styles.pageLabel}>{page + 1}페이지</span>
      <button
        className={styles.button}
        disabled={isLoading || !hasNext}
        onClick={() => onPageChange(page + 1)}
        type="button"
      >
        다음
      </button>
    </nav>
  );
}
