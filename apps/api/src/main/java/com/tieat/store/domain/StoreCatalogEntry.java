package com.tieat.store.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Rights-approved, public display metadata. It never represents an account or ledger scope. */
public final class StoreCatalogEntry {

    private final UUID id;
    private final String storeDisplayName;
    private final String brandDisplayName;
    private final String logoPath;

    public StoreCatalogEntry(UUID id, String storeDisplayName, String brandDisplayName, String logoPath) {
        this.id = Objects.requireNonNull(id, "Catalog entry id must be supplied");
        if (storeDisplayName == null || storeDisplayName.isBlank()) {
            throw new IllegalArgumentException("Catalog store display name must not be blank");
        }
        if (brandDisplayName == null || brandDisplayName.isBlank()) {
            throw new IllegalArgumentException("Catalog brand display name must not be blank");
        }
        this.storeDisplayName = storeDisplayName;
        this.brandDisplayName = brandDisplayName;
        this.logoPath = logoPath;
    }

    public UUID id() {
        return id;
    }

    public String storeDisplayName() {
        return storeDisplayName;
    }

    public String brandDisplayName() {
        return brandDisplayName;
    }

    public Optional<String> logoPath() {
        return Optional.ofNullable(logoPath);
    }
}
