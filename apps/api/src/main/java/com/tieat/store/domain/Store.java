package com.tieat.store.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** The source-of-truth profile for stores created through the new onboarding flow. */
public final class Store {

    private final StoreId id;
    private final String displayName;
    private final UUID catalogEntryId;
    private final StoreOnboardingStatus onboardingStatus;
    private final Instant createdAt;

    public Store(
        StoreId id,
        String displayName,
        UUID catalogEntryId,
        StoreOnboardingStatus onboardingStatus,
        Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "Store id must be supplied");
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Store display name must not be blank");
        }
        this.displayName = displayName;
        this.catalogEntryId = catalogEntryId;
        this.onboardingStatus = Objects.requireNonNull(onboardingStatus, "Onboarding status must be supplied");
        this.createdAt = Objects.requireNonNull(createdAt, "Store creation time must be supplied");
    }

    public Store completeOnboarding() {
        if (onboardingStatus == StoreOnboardingStatus.COMPLETE) {
            return this;
        }
        return new Store(id, displayName, catalogEntryId, StoreOnboardingStatus.COMPLETE, createdAt);
    }

    public StoreId id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public Optional<UUID> catalogEntryId() {
        return Optional.ofNullable(catalogEntryId);
    }

    public StoreOnboardingStatus onboardingStatus() {
        return onboardingStatus;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
