package com.tieat.ledger.application;

import com.tieat.ledger.domain.MealUsageId;

public final class MealUsageNotFoundException extends RuntimeException {

    public MealUsageNotFoundException(MealUsageId mealUsageId) {
        super("Meal usage not found: " + mealUsageId.value());
    }
}
