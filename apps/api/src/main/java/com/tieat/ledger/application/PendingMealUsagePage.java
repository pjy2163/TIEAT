package com.tieat.ledger.application;

import com.tieat.ledger.domain.MealUsage;
import java.util.List;
import java.util.Objects;

public record PendingMealUsagePage(List<MealUsage> items, int page, int size, boolean hasNext) {

    public PendingMealUsagePage {
        items = List.copyOf(Objects.requireNonNull(items, "Meal usage items must be supplied"));
    }
}
