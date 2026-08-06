package com.tieat.ledger.application;

import com.tieat.ledger.domain.MealUsageId;
import java.util.Objects;

public record ConfirmMealUsageCommand(
    MealUsageId mealUsageId,
    String staffInitials,
    long availablePrepaid
) {

    public ConfirmMealUsageCommand {
        Objects.requireNonNull(mealUsageId, "Meal usage id must be supplied");
        if (staffInitials == null || staffInitials.isBlank()) {
            throw new IllegalArgumentException("Staff initials must not be blank");
        }
        if (availablePrepaid < 0) {
            throw new IllegalArgumentException("Available prepaid must not be negative");
        }
    }

    /**
     * {@code availablePrepaid} is an authoritative application value. A future web adapter must
     * never map it from untrusted API input; it must be resolved from the applicable contract.
     */
    @Override
    public long availablePrepaid() {
        return availablePrepaid;
    }
}
