export type PartnerKind = "INDIVIDUAL" | "ORGANIZATION";
export type PartnerKindFilter = PartnerKind | "ALL";

export function partnerKindLabel(partnerKind: PartnerKind): "개인" | "단체" {
  return partnerKind === "INDIVIDUAL" ? "개인" : "단체";
}

export function filterPartnersByKind<T extends { partnerKind: PartnerKind }>(
  partners: readonly T[],
  filter: PartnerKindFilter,
): T[] {
  return filter === "ALL" ? [...partners] : partners.filter((partner) => partner.partnerKind === filter);
}
