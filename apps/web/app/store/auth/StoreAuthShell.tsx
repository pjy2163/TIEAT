import type { ReactNode } from "react";
import { storeAuthStyles } from "./StoreAuth.styles";

type StoreAuthShellProps = {
  children: ReactNode;
  description?: ReactNode;
  footer: ReactNode;
  title: string;
  titleId: string;
  width: "narrow" | "wide";
};

export function StoreAuthShell({ children, description, footer, title, titleId, width }: StoreAuthShellProps) {
  const cardWidth = width === "wide" ? storeAuthStyles.cardWide : storeAuthStyles.cardNarrow;

  return (
    <main className={storeAuthStyles.page}>
      <section className={`${storeAuthStyles.card} ${cardWidth}`} aria-labelledby={titleId}>
        <p className={storeAuthStyles.eyebrow}>TIEAT STORE</p>
        <h1 id={titleId} className={storeAuthStyles.title}>{title}</h1>
        {description !== undefined ? <p className={storeAuthStyles.description}>{description}</p> : null}
        {children}
        <p className={storeAuthStyles.footer}>{footer}</p>
      </section>
    </main>
  );
}
