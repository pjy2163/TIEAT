package com.tieat.partnership.domain;

import com.tieat.store.domain.StoreId;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class StoreArchivePinSecurity {

    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final StoreId storeId;
    private String pinHash;
    private int failedAttempts;
    private Instant failureWindowStartedAt;
    private Instant lockedUntil;
    private Instant updatedAt;
    private String updatedByLoginId;

    public StoreArchivePinSecurity(
        StoreId storeId,
        String pinHash,
        int failedAttempts,
        Instant failureWindowStartedAt,
        Instant lockedUntil,
        Instant updatedAt,
        String updatedByLoginId
    ) {
        this.storeId = Objects.requireNonNull(storeId, "Store id must be supplied");
        if (pinHash == null || pinHash.isBlank()) {
            throw new IllegalArgumentException("PIN hash must not be blank");
        }
        if (failedAttempts < 0 || failedAttempts > MAX_FAILED_ATTEMPTS) {
            throw new IllegalArgumentException("Failed attempts are out of range");
        }
        if ((failedAttempts == 0) != (failureWindowStartedAt == null)) {
            throw new IllegalArgumentException("Failure window must match failed attempts");
        }
        this.pinHash = pinHash;
        this.failedAttempts = failedAttempts;
        this.failureWindowStartedAt = failureWindowStartedAt;
        this.lockedUntil = lockedUntil;
        this.updatedAt = Objects.requireNonNull(updatedAt, "Updated timestamp must be supplied");
        this.updatedByLoginId = requireActor(updatedByLoginId);
    }

    public static StoreArchivePinSecurity initial(StoreId storeId, String pinHash, Instant now, String actorLoginId) {
        return new StoreArchivePinSecurity(storeId, pinHash, 0, null, null, now, actorLoginId);
    }

    public StoreId storeId() {
        return storeId;
    }

    public String pinHash() {
        return pinHash;
    }

    public int failedAttempts() {
        return failedAttempts;
    }

    public Instant failureWindowStartedAt() {
        return failureWindowStartedAt;
    }

    public Instant lockedUntil() {
        return lockedUntil;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public String updatedByLoginId() {
        return updatedByLoginId;
    }

    public boolean isLockedAt(Instant now) {
        Objects.requireNonNull(now, "Current timestamp must be supplied");
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    public void registerFailedAttempt(Instant now, String actorLoginId) {
        Objects.requireNonNull(now, "Current timestamp must be supplied");
        resetExpiredWindow(now);
        if (failureWindowStartedAt == null) {
            failureWindowStartedAt = now;
        }
        failedAttempts = Math.min(MAX_FAILED_ATTEMPTS, failedAttempts + 1);
        if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
            lockedUntil = now.plus(LOCK_DURATION);
        }
        updatedAt = now;
        updatedByLoginId = requireActor(actorLoginId);
    }

    public void markVerified(Instant now, String actorLoginId) {
        Objects.requireNonNull(now, "Current timestamp must be supplied");
        failedAttempts = 0;
        failureWindowStartedAt = null;
        lockedUntil = null;
        updatedAt = now;
        updatedByLoginId = requireActor(actorLoginId);
    }

    public void changePinHash(String nextPinHash, Instant now, String actorLoginId) {
        if (nextPinHash == null || nextPinHash.isBlank()) {
            throw new IllegalArgumentException("PIN hash must not be blank");
        }
        pinHash = nextPinHash;
        markVerified(now, actorLoginId);
    }

    private void resetExpiredWindow(Instant now) {
        if (failureWindowStartedAt != null && !now.isBefore(failureWindowStartedAt.plus(FAILURE_WINDOW))) {
            failedAttempts = 0;
            failureWindowStartedAt = null;
            lockedUntil = null;
        }
    }

    private static String requireActor(String actorLoginId) {
        if (actorLoginId == null || actorLoginId.isBlank()) {
            throw new IllegalArgumentException("PIN actor must not be blank");
        }
        return actorLoginId;
    }
}
