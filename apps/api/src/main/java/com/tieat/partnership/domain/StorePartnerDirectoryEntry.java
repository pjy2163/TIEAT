package com.tieat.partnership.domain;

import java.util.Objects;

/** A store-scoped partner contract projection for navigation and ledger filtering. */
public record StorePartnerDirectoryEntry(
    MealContractId mealContractId,
    String partnerDisplayName,
    PartnerKind partnerKind,
    MealContractPaymentType paymentType,
    boolean qrSelectable,
    String representativePhone,
    String representativeEmail
) {

    public StorePartnerDirectoryEntry(
        MealContractId mealContractId,
        String partnerDisplayName,
        PartnerKind partnerKind,
        MealContractPaymentType paymentType,
        boolean qrSelectable
    ) {
        this(mealContractId, partnerDisplayName, partnerKind, paymentType, qrSelectable, null, null);
    }

    public StorePartnerDirectoryEntry {
        Objects.requireNonNull(mealContractId, "Meal contract id must be supplied");
        if (partnerDisplayName == null || partnerDisplayName.isBlank()) {
            throw new IllegalArgumentException("Partner display name must not be blank");
        }
        Objects.requireNonNull(partnerKind, "Partner kind must be supplied");
        Objects.requireNonNull(paymentType, "Payment type must be supplied");
    }
}
