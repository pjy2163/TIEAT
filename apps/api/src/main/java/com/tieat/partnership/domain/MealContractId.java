package com.tieat.partnership.domain;

import java.util.Objects;
import java.util.UUID;

public record MealContractId(UUID value) {

    public MealContractId {
        Objects.requireNonNull(value, "Meal contract id must be supplied");
    }
}
