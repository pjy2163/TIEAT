import Link from "next/link";
import { storeProfileStyles as styles } from "./StoreProfileView.styles";

type ProfileStateProps = Readonly<{
  actionHref?: string;
  actionLabel?: string;
  description: string;
  onRetry?: () => void;
  title: string;
}>;

export function ProfileState({ actionHref, actionLabel, description, onRetry, title }: ProfileStateProps) {
  return (
    <main className={styles.page}>
      <section className={styles.container} aria-live="polite">
        <div className={styles.state}>
          <h1 className={styles.stateTitle}>{title}</h1>
          <p className={styles.stateDescription}>{description}</p>
          {onRetry ? <button className={styles.stateAction} onClick={onRetry} type="button">다시 시도</button> : null}
          {actionHref && actionLabel ? <Link className={styles.stateAction} href={actionHref}>{actionLabel}</Link> : null}
        </div>
      </section>
    </main>
  );
}
