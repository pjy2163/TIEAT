package com.tieat.ledger.application;

import com.tieat.ledger.domain.MealUsage;
import java.util.List;
import java.util.Objects;

public record MonthlyMealUsagePage(String month, List<MealUsage> items, int page, int size, boolean hasNext) {

    public MonthlyMealUsagePage {
        Objects.requireNonNull(month, "Month must be supplied");
        items = List.copyOf(Objects.requireNonNull(items, "Monthly meal usage items must be supplied"));
    }
}
