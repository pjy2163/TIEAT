package com.tieat.ledger.application;

import com.tieat.partnership.domain.MealContractId;

public final class MealContractNotFoundException extends RuntimeException {

    public MealContractNotFoundException(MealContractId mealContractId) {
        super("Meal contract not found: " + mealContractId.value());
    }
}
