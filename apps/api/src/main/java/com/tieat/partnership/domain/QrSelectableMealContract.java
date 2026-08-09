package com.tieat.partnership.domain;

import java.util.Objects;

public record QrSelectableMealContract(MealContractId mealContractId, String partnerDisplayName) {

    public QrSelectableMealContract {
        Objects.requireNonNull(mealContractId, "Meal contract id must be supplied");
        if (partnerDisplayName == null || partnerDisplayName.isBlank()) {
            throw new IllegalArgumentException("Partner display name must not be blank");
        }
    }
}
