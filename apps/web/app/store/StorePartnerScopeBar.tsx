"use client";

import { useState, type ChangeEvent } from "react";
import { filterPartnersByKind, type PartnerKindFilter } from "@/lib/partner-kind";
import { useStorePartnerContext } from "./StorePartnerContext";
import { StorePartnerKindFilter } from "./StorePartnerKindFilter";

const styles = {
  shell: "sticky top-14 z-20 -mx-5 mb-6 border-b border-[var(--border)] bg-white/95 px-5 py-3 shadow-[0_4px_18px_rgba(17,24,39,0.04)] backdrop-blur supports-[backdrop-filter]:bg-white/85 sm:-mx-8 sm:px-8 lg:top-0",
  inner: "mx-auto flex w-full max-w-5xl flex-col gap-2 sm:flex-row sm:items-center sm:justify-between sm:gap-4",
  label: "grid min-w-0 flex-1 gap-1.5 sm:flex sm:items-center sm:gap-3",
  labelText: "shrink-0 text-xs font-semibold tracking-[0.02em] text-[var(--text-secondary)]",
  select: "h-11 min-w-0 w-full rounded-lg border border-[var(--border-strong)] bg-white px-3 text-sm font-medium text-[var(--text-primary)] outline-none focus:border-[var(--accent)] focus:ring-2 focus:ring-[#dbe4ff] disabled:cursor-not-allowed disabled:bg-[var(--surface-subtle)] disabled:text-[var(--text-muted)] sm:max-w-sm",
  kindFilter: "h-11 min-w-0 rounded-lg border border-[var(--border-strong)] bg-white px-3 text-sm font-medium text-[var(--text-primary)] outline-none focus:border-[var(--accent)] focus:ring-2 focus:ring-[#dbe4ff] disabled:cursor-not-allowed disabled:bg-[var(--surface-subtle)] disabled:text-[var(--text-muted)] sm:w-28",
  status: "text-xs leading-5 text-[var(--text-muted)] sm:shrink-0",
  error: "text-xs leading-5 text-[var(--danger)] sm:shrink-0",
} as const;

export function StorePartnerScopeBar() {
  const {
    isMonthlyLedgerRoute,
    partners,
    directoryState,
    scopeState,
    selectedMealContractId,
    selectPartner,
  } = useStorePartnerContext();
  const [partnerKindFilter, setPartnerKindFilter] = useState<PartnerKindFilter>("ALL");
  const visiblePartners = filterPartnersByKind(partners, partnerKindFilter);
  const selectedPartner = partners.find((partner) => partner.mealContractId === selectedMealContractId);

  if (!isMonthlyLedgerRoute) return null;

  function onKindFilterChange(nextFilter: PartnerKindFilter) {
    setPartnerKindFilter(nextFilter);
    if (selectedPartner && nextFilter !== "ALL" && selectedPartner.partnerKind !== nextFilter) {
      selectPartner(null);
    }
  }

  function onChange(event: ChangeEvent<HTMLSelectElement>) {
    selectPartner(event.target.value || null);
  }

  return (
    <div className={styles.shell} data-store-partner-scope-bar>
      <div className={styles.inner}>
        <div className={styles.label}>
          <span className={styles.labelText}>협력사</span>
          <StorePartnerKindFilter
            ariaLabel="협력사 유형 필터"
            className={styles.kindFilter}
            disabled={directoryState !== "ready" || scopeState === "invalid"}
            onChange={onKindFilterChange}
            value={partnerKindFilter}
          />
          <select
            aria-label="협력사 선택"
            className={styles.select}
            disabled={directoryState !== "ready" || scopeState === "invalid"}
            id="store-partner-scope"
            onChange={onChange}
            value={scopeState === "selected" ? selectedMealContractId ?? "" : ""}
          >
            <option value="">전체</option>
            {visiblePartners.map((partner) => (
              <option key={partner.mealContractId} value={partner.mealContractId}>
                {partner.partnerDisplayName}
              </option>
            ))}
          </select>
        </div>
        {directoryState === "loading" ? <span className={styles.status} role="status">협력사 목록을 불러오는 중입니다.</span> : null}
        {directoryState === "error" ? <span className={styles.error} role="alert">협력사 목록을 불러오지 못했습니다. 전체 장부는 계속 볼 수 있습니다.</span> : null}
      </div>
    </div>
  );
}
