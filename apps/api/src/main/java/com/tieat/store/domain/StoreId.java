package com.tieat.store.domain;

import java.util.Objects;
import java.util.UUID;

public record StoreId(UUID value) {

    public StoreId {
        Objects.requireNonNull(value, "Store id must be supplied");
    }
}
