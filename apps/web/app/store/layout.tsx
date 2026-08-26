import { Suspense } from "react";
import { StorePartnerProvider } from "./StorePartnerContext";
import { StoreWorkspaceShell } from "./StoreWorkspaceShell";

export default function StoreLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <Suspense fallback={<div aria-busy="true" className="min-h-dvh bg-[var(--surface-subtle)]" />}>
      <StorePartnerProvider>
        <StoreWorkspaceShell>{children}</StoreWorkspaceShell>
      </StorePartnerProvider>
    </Suspense>
  );
}
