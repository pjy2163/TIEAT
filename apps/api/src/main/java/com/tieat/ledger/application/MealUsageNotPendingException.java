package com.tieat.ledger.application;

import com.tieat.ledger.domain.MealUsageId;

public final class MealUsageNotPendingException extends RuntimeException {

    public MealUsageNotPendingException(MealUsageId mealUsageId) {
        super("Meal usage is no longer pending: " + mealUsageId.value());
    }
}
