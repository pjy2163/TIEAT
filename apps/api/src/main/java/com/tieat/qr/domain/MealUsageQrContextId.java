package com.tieat.qr.domain;

import java.util.Objects;
import java.util.UUID;

public record MealUsageQrContextId(UUID value) {

    public MealUsageQrContextId {
        Objects.requireNonNull(value, "Meal usage QR context id must be supplied");
    }
}
