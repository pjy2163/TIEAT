import { forwardRef, type MouseEventHandler } from "react";
import { storeActionStyles } from "./StoreAction.styles";

type StoreCloseButtonProps = Readonly<{
  className?: string;
  disabled?: boolean;
  onClick: MouseEventHandler<HTMLButtonElement>;
}>;

export const StoreCloseButton = forwardRef<HTMLButtonElement, StoreCloseButtonProps>(
  function StoreCloseButton({ className, disabled = false, onClick }, ref) {
    return (
      <button
        aria-label="닫기"
        className={className ? `${storeActionStyles.close} ${className}` : storeActionStyles.close}
        disabled={disabled}
        onClick={onClick}
        ref={ref}
        type="button"
      >
        <span aria-hidden="true">×</span>
      </button>
    );
  },
);
