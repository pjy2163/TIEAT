package com.tieat.identity.domain;

import com.tieat.store.domain.StoreId;
import java.util.Objects;

public record StoreAccount(String loginId, String passwordHash, StoreId storeId, boolean enabled) {

    public StoreAccount {
        if (loginId == null || loginId.isBlank()) {
            throw new IllegalArgumentException("Login id must not be blank");
        }
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("Password hash must not be blank");
        }
        Objects.requireNonNull(storeId, "Store id must be supplied");
    }
}
