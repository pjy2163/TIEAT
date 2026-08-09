package com.tieat.ledger.application;

import com.tieat.ledger.domain.MealUsageId;
import com.tieat.store.domain.StoreId;
import java.util.Objects;

public record RejectMealUsageCommand(MealUsageId mealUsageId, StoreId actorStoreId, String actorLoginId) {

    public RejectMealUsageCommand {
        Objects.requireNonNull(mealUsageId, "Meal usage id must be supplied");
        Objects.requireNonNull(actorStoreId, "Actor store id must be supplied");
        if (actorLoginId == null || actorLoginId.isBlank()) {
            throw new IllegalArgumentException("Actor login id must not be blank");
        }
    }
}
