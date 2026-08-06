package com.tieat.ledger.application;

import com.tieat.ledger.domain.MealUsage;
import com.tieat.partnership.domain.MealContract;

public final class MealUsageContractScopeMismatchException extends RuntimeException {

    public MealUsageContractScopeMismatchException(MealUsage mealUsage, MealContract mealContract) {
        super(
            "Meal usage store " + mealUsage.storeId().value()
                + " does not match meal contract store " + mealContract.storeId().value()
        );
    }
}
