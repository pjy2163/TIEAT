package com.tieat.partnership.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "store_archive_pin_security")
class StoreArchivePinSecurityJpaEntity {

    @Id
    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "pin_hash", nullable = false, length = 100)
    private String pinHash;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "failure_window_started_at")
    private Instant failureWindowStartedAt;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by_login_id", nullable = false, length = 120)
    private String updatedByLoginId;

    protected StoreArchivePinSecurityJpaEntity() {
    }

    StoreArchivePinSecurityJpaEntity(
        UUID storeId,
        String pinHash,
        int failedAttempts,
        Instant failureWindowStartedAt,
        Instant lockedUntil,
        Instant updatedAt,
        String updatedByLoginId
    ) {
        this.storeId = storeId;
        this.pinHash = pinHash;
        this.failedAttempts = failedAttempts;
        this.failureWindowStartedAt = failureWindowStartedAt;
        this.lockedUntil = lockedUntil;
        this.updatedAt = updatedAt;
        this.updatedByLoginId = updatedByLoginId;
    }

    UUID storeId() {
        return storeId;
    }

    String pinHash() {
        return pinHash;
    }

    int failedAttempts() {
        return failedAttempts;
    }

    Instant failureWindowStartedAt() {
        return failureWindowStartedAt;
    }

    Instant lockedUntil() {
        return lockedUntil;
    }

    Instant updatedAt() {
        return updatedAt;
    }

    String updatedByLoginId() {
        return updatedByLoginId;
    }
}
