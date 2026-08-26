package com.tieat.ledger.application;

import com.tieat.partnership.domain.MealContractId;
import com.tieat.ledger.domain.PublicMealUsageIdempotency;
import java.util.Objects;
import java.util.UUID;

public record CreatePublicMealUsageCommand(
    String rawQrToken,
    UUID idempotencyKey,
    MealContractId mealContractId,
    long amount,
    String rawRequestKey,
    String customerName
) {

    public static final long MAX_AMOUNT_MINOR = 1_000_000;
    public static final int MAX_CUSTOMER_NAME_LENGTH = 80;

    public CreatePublicMealUsageCommand {
        Objects.requireNonNull(rawQrToken, "Raw QR token must be supplied");
        Objects.requireNonNull(idempotencyKey, "Idempotency key must be supplied");
        Objects.requireNonNull(mealContractId, "Meal contract id must be supplied");
        if (amount <= 0 || amount > MAX_AMOUNT_MINOR) {
            throw new IllegalArgumentException("Usage amount must be between 1 and 1000000");
        }
        PublicMealUsageIdempotency.hashRequestKey(rawRequestKey);
        customerName = normalizeCustomerName(customerName);
    }

    public String requestKeyHash() {
        return PublicMealUsageIdempotency.hashRequestKey(rawRequestKey);
    }

    private static String normalizeCustomerName(String value) {
        String normalized = Objects.requireNonNull(value, "Customer name must be supplied").strip();
        if (normalized.isEmpty() || normalized.length() > MAX_CUSTOMER_NAME_LENGTH) {
            throw new IllegalArgumentException("Customer name must be between 1 and 80 characters");
        }
        return normalized;
    }
}
