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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "token_ciphertext", columnDefinition = "BYTEA")
    private byte[] tokenCiphertext;

    @Column(name = "token_nonce", columnDefinition = "BYTEA")
    private byte[] tokenNonce;

    @Column(name = "token_key_version")
    private Integer tokenKeyVersion;

    @Column(name = "accepting_new_requests", nullable = false)
    private boolean acceptingNewRequests;

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

    Instant createdAt() {
        return createdAt;
    }

    Instant expiresAt() {
        return expiresAt;
    }

    Instant revokedAt() {
        return revokedAt;
    }

    byte[] tokenCiphertext() {
        return tokenCiphertext == null ? null : tokenCiphertext.clone();
    }

    byte[] tokenNonce() {
        return tokenNonce == null ? null : tokenNonce.clone();
    }

    Integer tokenKeyVersion() {
        return tokenKeyVersion;
    }

    boolean acceptingNewRequests() {
        return acceptingNewRequests;
    }
}
