package com.tieat.ledger.application;

import com.tieat.ledger.domain.MealUsageId;
import java.util.Objects;

public record ConfirmMealUsageCommand(
    MealUsageId mealUsageId,
    String staffInitials
) {

    public ConfirmMealUsageCommand {
        Objects.requireNonNull(mealUsageId, "Meal usage id must be supplied");
        if (staffInitials == null || staffInitials.isBlank()) {
            throw new IllegalArgumentException("Staff initials must not be blank");
        }
    }
}
