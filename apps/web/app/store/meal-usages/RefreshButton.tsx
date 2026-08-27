import { storeActionStyles } from "../StoreAction.styles";

type RefreshButtonProps = {
  className?: string;
  disabled?: boolean;
  isRefreshing: boolean;
  onClick: () => void;
};

const baseClassName = `${storeActionStyles.neutral} h-11 shrink-0`;

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
