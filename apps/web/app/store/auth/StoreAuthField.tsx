import type { ReactNode } from "react";
import { storeAuthStyles } from "./StoreAuth.styles";

type StoreAuthFieldProps = {
  children: ReactNode;
  hint?: ReactNode;
  htmlFor: string;
  label: ReactNode;
};

export function StoreAuthField({ children, hint, htmlFor, label }: StoreAuthFieldProps) {
  return (
    <div className={storeAuthStyles.field}>
      <label className={storeAuthStyles.label} htmlFor={htmlFor}>{label}</label>
      {children}
      {hint ? <p className={storeAuthStyles.hint}>{hint}</p> : null}
    </div>
  );
}
