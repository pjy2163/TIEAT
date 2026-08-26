"use client";

import type { PartnerKind } from "@/lib/partner-kind";

const styles = {
  field: "space-y-2",
  label: "block text-sm font-semibold text-[var(--text-primary)]",
  choiceGrid: "grid gap-2 sm:grid-cols-2",
  choice: "flex min-h-12 cursor-pointer items-center gap-2 rounded-lg border border-[var(--border-strong)] px-3 text-sm text-[var(--text-primary)] has-[:checked]:border-[var(--accent)] has-[:checked]:bg-[#f4f6ff] has-[:checked]:font-semibold has-[:disabled]:cursor-not-allowed has-[:disabled]:opacity-60",
} as const;

export type StorePartnerKindFieldsProps = Readonly<{
  disabled?: boolean;
  onPartnerKindChange: (value: PartnerKind) => void;
  partnerKind: PartnerKind | "";
}>;

export function StorePartnerKindFields({
  disabled = false,
  onPartnerKindChange,
  partnerKind,
}: StorePartnerKindFieldsProps) {
  return (
    <fieldset className={styles.field} disabled={disabled}>
      <legend className={styles.label}>협력사 유형</legend>
      <div className={styles.choiceGrid}>
        <label className={styles.choice}>
          <input
            checked={partnerKind === "ORGANIZATION"}
            name="partnerKind"
            onChange={() => onPartnerKindChange("ORGANIZATION")}
            type="radio"
          />
          단체
        </label>
        <label className={styles.choice}>
          <input
            checked={partnerKind === "INDIVIDUAL"}
            name="partnerKind"
            onChange={() => onPartnerKindChange("INDIVIDUAL")}
            type="radio"
          />
          개인
        </label>
      </div>
    </fieldset>
  );
}
