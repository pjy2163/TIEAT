package com.tieat.partnership.domain;

import com.tieat.store.domain.StoreId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MealContractPaymentTermAudit(
    UUID id,
    StoreId storeId,
    MealContractId mealContractId,
    String actorLoginId,
    MealContractPaymentType previousPaymentType,
    MealContractPaymentType newPaymentType,
    long prepaidBalanceBefore,
    long prepaidBalanceAfter,
    Instant occurredAt
) {

    public MealContractPaymentTermAudit {
        Objects.requireNonNull(id, "Audit id must be supplied");
        Objects.requireNonNull(storeId, "Store id must be supplied");
        Objects.requireNonNull(mealContractId, "Meal contract id must be supplied");
        if (actorLoginId == null || actorLoginId.isBlank()) {
            throw new IllegalArgumentException("Audit actor must not be blank");
        }
        Objects.requireNonNull(previousPaymentType, "Previous payment type must be supplied");
        Objects.requireNonNull(newPaymentType, "New payment type must be supplied");
        if (previousPaymentType == newPaymentType) {
            throw new IllegalArgumentException("Payment term audit must record a change");
        }
        if (prepaidBalanceBefore < 0 || prepaidBalanceAfter < 0) {
            throw new IllegalArgumentException("Audit balances must not be negative");
        }
        if (newPaymentType == MealContractPaymentType.POSTPAID && prepaidBalanceAfter != 0) {
            throw new IllegalArgumentException("Postpaid audit balance must be zero");
        }
        Objects.requireNonNull(occurredAt, "Audit timestamp must be supplied");
    }
}
