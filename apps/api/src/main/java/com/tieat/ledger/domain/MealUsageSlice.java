package com.tieat.ledger.domain;

import java.util.List;
import java.util.Objects;

public record MealUsageSlice(List<MealUsage> items, boolean hasNext) {

    public MealUsageSlice {
        items = List.copyOf(Objects.requireNonNull(items, "Meal usage items must be supplied"));
    }
}
