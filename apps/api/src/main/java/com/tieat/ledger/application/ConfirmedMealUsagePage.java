package com.tieat.ledger.application;

import com.tieat.ledger.domain.MonthlyMealUsageRow;
import java.util.List;
import java.util.Objects;

public record ConfirmedMealUsagePage(
    String fromDate,
    String toDate,
    List<MonthlyMealUsageRow> items,
    int page,
    int size,
    boolean hasNext,
    long totalAmountMinor
) {

    public ConfirmedMealUsagePage {
        Objects.requireNonNull(fromDate, "From date must be supplied");
        Objects.requireNonNull(toDate, "To date must be supplied");
        items = List.copyOf(Objects.requireNonNull(items, "Confirmed meal usage items must be supplied"));
        if (totalAmountMinor < 0) {
            throw new IllegalArgumentException("Confirmed meal usage total must not be negative");
        }
    }
}
