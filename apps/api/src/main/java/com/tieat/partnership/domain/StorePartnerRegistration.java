package com.tieat.partnership.domain;

import com.tieat.store.domain.StoreId;
import java.util.Objects;
import java.util.UUID;

/** Immutable input snapshot used to make one partner-registration request idempotent per store. */
public record StorePartnerRegistration(
    StoreId storeId,
    UUID idempotencyKey,
    PartnerOrganizationId partnerOrganizationId,
    MealContractId mealContractId,
    String partnerDisplayName,
    PartnerKind partnerKind,
    MealContractPaymentType paymentType,
    long initialPrepaidBalanceMinor,
    boolean qrSelectable
) {

    public StorePartnerRegistration {
        Objects.requireNonNull(storeId, "Store id must be supplied");
        Objects.requireNonNull(idempotencyKey, "Idempotency key must be supplied");
        Objects.requireNonNull(partnerOrganizationId, "Partner organization id must be supplied");
        Objects.requireNonNull(mealContractId, "Meal contract id must be supplied");
        if (partnerDisplayName == null || partnerDisplayName.isBlank()) {
            throw new IllegalArgumentException("Partner display name must not be blank");
        }
        Objects.requireNonNull(partnerKind, "Partner kind must be supplied");
        Objects.requireNonNull(paymentType, "Payment type must be supplied");
        if (initialPrepaidBalanceMinor < 0) {
            throw new IllegalArgumentException("Initial prepaid balance must not be negative");
        }
        if (paymentType == MealContractPaymentType.POSTPAID && initialPrepaidBalanceMinor != 0) {
            throw new IllegalArgumentException("Postpaid registrations must have zero initial prepaid balance");
        }
    }

    public boolean matches(
        String normalizedPartnerDisplayName,
        PartnerKind requestedPartnerKind,
        MealContractPaymentType requestedPaymentType,
        long requestedInitialPrepaidBalanceMinor,
        boolean requestedQrSelectable
    ) {
        return partnerDisplayName.equals(normalizedPartnerDisplayName)
            && partnerKind == requestedPartnerKind
            && paymentType == requestedPaymentType
            && initialPrepaidBalanceMinor == requestedInitialPrepaidBalanceMinor
            && qrSelectable == requestedQrSelectable;
    }
}
