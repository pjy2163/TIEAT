package com.tieat.qr.domain;

import com.tieat.store.domain.StoreId;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class MealUsageQrContext {

    public static final Duration DEFAULT_LIFETIME = Duration.ofDays(90);

    private final MealUsageQrContextId id;
    private final StoreId storeId;
    private final String storeDisplayName;
    private final String tokenHash;
    private final Instant expiresAt;
    private final Instant revokedAt;

    public MealUsageQrContext(
        MealUsageQrContextId id,
        StoreId storeId,
        String storeDisplayName,
        String tokenHash,
        Instant expiresAt,
        Instant revokedAt
    ) {
        this.id = Objects.requireNonNull(id, "Meal usage QR context id must be supplied");
        this.storeId = Objects.requireNonNull(storeId, "Store id must be supplied");
        if (storeDisplayName == null || storeDisplayName.isBlank()) {
            throw new IllegalArgumentException("Store display name must not be blank");
        }
        this.storeDisplayName = storeDisplayName;
        if (tokenHash == null || !tokenHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("QR token hash must be a SHA-256 hex digest");
        }
        this.tokenHash = tokenHash;
        this.expiresAt = Objects.requireNonNull(expiresAt, "QR expiry must be supplied");
        this.revokedAt = revokedAt;
    }

    public static MealUsageQrContext issue(
        MealUsageQrContextId id,
        StoreId storeId,
        String storeDisplayName,
        String tokenHash,
        Instant issuedAt
    ) {
        Objects.requireNonNull(issuedAt, "QR issue time must be supplied");
        return new MealUsageQrContext(id, storeId, storeDisplayName, tokenHash, issuedAt.plus(DEFAULT_LIFETIME), null);
    }

    public boolean isActiveAt(Instant instant) {
        Objects.requireNonNull(instant, "Active check time must be supplied");
        return revokedAt == null && expiresAt.isAfter(instant);
    }

    public MealUsageQrContextId id() {
        return id;
    }

    public StoreId storeId() {
        return storeId;
    }

    public String storeDisplayName() {
        return storeDisplayName;
    }

    public String tokenHash() {
        return tokenHash;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Optional<Instant> revokedAt() {
        return Optional.ofNullable(revokedAt);
    }
}
