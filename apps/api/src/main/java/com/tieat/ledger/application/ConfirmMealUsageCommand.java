package com.tieat.ledger.application;

import com.tieat.ledger.domain.MealUsageId;
import com.tieat.store.domain.StoreId;
import java.util.Objects;

public record ConfirmMealUsageCommand(
    MealUsageId mealUsageId,
    StoreId actorStoreId,
    String staffInitials
) {

    public ConfirmMealUsageCommand {
        Objects.requireNonNull(mealUsageId, "Meal usage id must be supplied");
        Objects.requireNonNull(actorStoreId, "Actor store id must be supplied");
        if (staffInitials == null || staffInitials.isBlank()) {
            throw new IllegalArgumentException("Staff initials must not be blank");
        }
    }
}
