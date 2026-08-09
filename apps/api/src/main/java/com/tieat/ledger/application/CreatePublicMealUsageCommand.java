package com.tieat.ledger.application;

import com.tieat.partnership.domain.MealContractId;
import java.util.Objects;
import java.util.UUID;

public record CreatePublicMealUsageCommand(
    String rawQrToken,
    UUID idempotencyKey,
    MealContractId mealContractId,
    long amount
) {

    public static final long MAX_AMOUNT_MINOR = 1_000_000;

    public CreatePublicMealUsageCommand {
        Objects.requireNonNull(rawQrToken, "Raw QR token must be supplied");
        Objects.requireNonNull(idempotencyKey, "Idempotency key must be supplied");
        Objects.requireNonNull(mealContractId, "Meal contract id must be supplied");
        if (amount <= 0 || amount > MAX_AMOUNT_MINOR) {
            throw new IllegalArgumentException("Usage amount must be between 1 and 1000000");
        }
    }
}
