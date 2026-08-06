package com.tieat.ledger.domain;

import java.util.Objects;
import java.util.UUID;

public record MealUsageId(UUID value) {

    public MealUsageId {
        Objects.requireNonNull(value, "Meal usage id must be supplied");
    }

    public static MealUsageId newId() {
        return new MealUsageId(UUID.randomUUID());
    }
}
