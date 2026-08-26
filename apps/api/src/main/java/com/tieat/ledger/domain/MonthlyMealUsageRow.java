package com.tieat.ledger.domain;

import java.util.Objects;

/**
 * A confirmed monthly ledger row with the read-only settlement allocation signal needed by the
 * monthly view. The allocation itself remains owned by the settlement persistence boundary.
 */
public record MonthlyMealUsageRow(MealUsage mealUsage, boolean settlementAllocationExists) {

    public MonthlyMealUsageRow {
        Objects.requireNonNull(mealUsage, "Monthly meal usage must be supplied");
    }
}
