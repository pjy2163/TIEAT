package com.tieat.ledger.application;

import com.tieat.ledger.domain.MealUsage;
import java.util.List;
import java.util.Objects;

public record MonthlyMealUsagePage(
    String month,
    String fromMonth,
    String toMonth,
    List<MealUsage> items,
    int page,
    int size,
    boolean hasNext,
    long totalAmountMinor
) {

    public MonthlyMealUsagePage(String month, List<MealUsage> items, int page, int size, boolean hasNext) {
        this(month, month, month, items, page, size, hasNext, 0);
    }

    public MonthlyMealUsagePage {
        Objects.requireNonNull(month, "Month must be supplied");
        Objects.requireNonNull(fromMonth, "From month must be supplied");
        Objects.requireNonNull(toMonth, "To month must be supplied");
        items = List.copyOf(Objects.requireNonNull(items, "Monthly meal usage items must be supplied"));
        if (totalAmountMinor < 0) {
            throw new IllegalArgumentException("Monthly meal usage total must not be negative");
        }
    }
}
