package com.tieat.ledger.domain;

import java.util.List;
import java.util.Objects;

public record MonthlyMealUsageSlice(List<MonthlyMealUsageRow> items, boolean hasNext) {

    public MonthlyMealUsageSlice {
        items = List.copyOf(Objects.requireNonNull(items, "Monthly meal usage items must be supplied"));
    }
}
