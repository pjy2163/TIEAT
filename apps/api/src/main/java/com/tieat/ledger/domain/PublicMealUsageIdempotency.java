package com.tieat.ledger.domain;

import com.tieat.partnership.domain.MealContractId;
import com.tieat.qr.domain.MealUsageQrContextId;
import java.util.Objects;
import java.util.UUID;

public record PublicMealUsageIdempotency(
    MealUsageQrContextId qrContextId,
    UUID idempotencyKey,
    MealContractId mealContractId,
    long amount,
    MealUsageId mealUsageId
) {

    public PublicMealUsageIdempotency {
        Objects.requireNonNull(qrContextId, "Meal usage QR context id must be supplied");
        Objects.requireNonNull(idempotencyKey, "Idempotency key must be supplied");
        Objects.requireNonNull(mealContractId, "Meal contract id must be supplied");
        if (amount <= 0) {
            throw new IllegalArgumentException("Usage amount must be positive");
        }
        Objects.requireNonNull(mealUsageId, "Meal usage id must be supplied");
    }
}
