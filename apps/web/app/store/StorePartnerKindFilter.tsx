"use client";

import type { ChangeEvent } from "react";
import type { PartnerKindFilter } from "@/lib/partner-kind";

export type StorePartnerKindFilterProps = Readonly<{
  ariaLabel?: string;
  className?: string;
  disabled?: boolean;
  onChange: (value: PartnerKindFilter) => void;
  value: PartnerKindFilter;
}>;

function parseFilter(value: string): PartnerKindFilter {
  if (value === "INDIVIDUAL" || value === "ORGANIZATION") return value;
  return "ALL";
}

export function StorePartnerKindFilter({
  ariaLabel = "협력사 유형 필터",
  className,
  disabled = false,
  onChange,
  value,
}: StorePartnerKindFilterProps) {
  function handleChange(event: ChangeEvent<HTMLSelectElement>) {
    onChange(parseFilter(event.target.value));
  }

  return (
    <select
      aria-label={ariaLabel}
      className={className}
      disabled={disabled}
      onChange={handleChange}
      value={value}
    >
      <option value="ALL">전체</option>
      <option value="ORGANIZATION">단체</option>
      <option value="INDIVIDUAL">개인</option>
    </select>
  );
}
