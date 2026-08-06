package com.tieat.ledger.application;

import com.tieat.partnership.domain.MealContractId;
import com.tieat.store.domain.StoreId;
import com.tieat.ledger.domain.EntrySource;
import java.util.Objects;

public record CreateMealUsageCommand(
    StoreId storeId,
    MealContractId mealContractId,
    EntrySource entrySource,
    long amount
) {

    public CreateMealUsageCommand {
        Objects.requireNonNull(storeId, "Store id must be supplied");
        Objects.requireNonNull(mealContractId, "Meal contract id must be supplied");
        Objects.requireNonNull(entrySource, "Entry source must be supplied");
        if (amount <= 0) {
            throw new IllegalArgumentException("Usage amount must be positive");
        }
    }
}
