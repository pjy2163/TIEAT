package com.tieat.ledger.application;

import com.tieat.ledger.domain.MealUsageId;
import java.util.Objects;

public final class MealUsageAlreadyConfirmedException extends RuntimeException {

    public MealUsageAlreadyConfirmedException(MealUsageId mealUsageId) {
        super("Meal usage is already confirmed: " + Objects.requireNonNull(mealUsageId).value());
    }
}
