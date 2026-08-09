package com.tieat.qr.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "meal_usage_qr_contexts")
class MealUsageQrContextJpaEntity {

    @Id
    private UUID id;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "store_display_name", nullable = false, columnDefinition = "TEXT")
    private String storeDisplayName;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected MealUsageQrContextJpaEntity() {
    }

    UUID id() {
        return id;
    }

    UUID storeId() {
        return storeId;
    }

    String storeDisplayName() {
        return storeDisplayName;
    }

    String tokenHash() {
        return tokenHash;
    }

    Instant expiresAt() {
        return expiresAt;
    }

    Instant revokedAt() {
        return revokedAt;
    }
}
