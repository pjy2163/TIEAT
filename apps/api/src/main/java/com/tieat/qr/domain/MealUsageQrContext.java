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
    private final Instant issuedAt;
    private final Instant expiresAt;
    private final Instant revokedAt;
    private final ProtectedToken protectedToken;

    public MealUsageQrContext(
        MealUsageQrContextId id,
        StoreId storeId,
        String storeDisplayName,
        String tokenHash,
        Instant issuedAt,
        Instant expiresAt,
        Instant revokedAt
    ) {
        this(id, storeId, storeDisplayName, tokenHash, issuedAt, expiresAt, revokedAt, null);
    }

    public MealUsageQrContext(
        MealUsageQrContextId id,
        StoreId storeId,
        String storeDisplayName,
        String tokenHash,
        Instant issuedAt,
        Instant expiresAt,
        Instant revokedAt,
        ProtectedToken protectedToken
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
        this.issuedAt = Objects.requireNonNull(issuedAt, "QR issue time must be supplied");
        this.expiresAt = Objects.requireNonNull(expiresAt, "QR expiry must be supplied");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("QR expiry must be after issue time");
        }
        this.revokedAt = revokedAt;
        this.protectedToken = protectedToken;
    }

    public static MealUsageQrContext issue(
        MealUsageQrContextId id,
        StoreId storeId,
        String storeDisplayName,
        String tokenHash,
        Instant issuedAt
    ) {
        Objects.requireNonNull(issuedAt, "QR issue time must be supplied");
        return issue(id, storeId, storeDisplayName, tokenHash, issuedAt, null);
    }

    public static MealUsageQrContext issue(
        MealUsageQrContextId id,
        StoreId storeId,
        String storeDisplayName,
        String tokenHash,
        Instant issuedAt,
        ProtectedToken protectedToken
    ) {
        Objects.requireNonNull(issuedAt, "QR issue time must be supplied");
        return new MealUsageQrContext(
            id,
            storeId,
            storeDisplayName,
            tokenHash,
            issuedAt,
            issuedAt.plus(DEFAULT_LIFETIME),
            null,
            protectedToken
        );
    }

    public boolean isActiveAt(Instant instant) {
        Objects.requireNonNull(instant, "Active check time must be supplied");
        return revokedAt == null && expiresAt.isAfter(instant);
    }

    public MealUsageQrContext renewUntil(Instant renewedExpiresAt) {
        Objects.requireNonNull(renewedExpiresAt, "QR renewal expiry must be supplied");
        if (revokedAt != null) {
            throw new IllegalStateException("Revoked QR context cannot be renewed");
        }
        if (!renewedExpiresAt.isAfter(expiresAt)) {
            throw new IllegalArgumentException("QR renewal expiry must be after the current expiry");
        }
        return new MealUsageQrContext(
            id,
            storeId,
            storeDisplayName,
            tokenHash,
            issuedAt,
            renewedExpiresAt,
            null,
            protectedToken
        );
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

    public Instant issuedAt() {
        return issuedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Optional<Instant> revokedAt() {
        return Optional.ofNullable(revokedAt);
    }

    public Optional<ProtectedToken> protectedToken() {
        return Optional.ofNullable(protectedToken);
    }

    public record ProtectedToken(byte[] ciphertext, byte[] nonce, int keyVersion) {

        public ProtectedToken {
            ciphertext = Objects.requireNonNull(ciphertext, "Protected QR token ciphertext must be supplied").clone();
            nonce = Objects.requireNonNull(nonce, "Protected QR token nonce must be supplied").clone();
            if (nonce.length != 12) {
                throw new IllegalArgumentException("Protected QR token nonce must be 12 bytes");
            }
            if (keyVersion <= 0) {
                throw new IllegalArgumentException("Protected QR token key version must be positive");
            }
        }

        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }

        @Override
        public byte[] nonce() {
            return nonce.clone();
        }
    }
}
