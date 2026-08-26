package com.tieat.store.adapter.out.persistence;

import com.tieat.store.domain.StoreOnboardingStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "stores")
class StoreJpaEntity {

    @Id
    private UUID id;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "catalog_entry_id")
    private UUID catalogEntryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "onboarding_status", nullable = false, length = 32)
    private StoreOnboardingStatus onboardingStatus;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected StoreJpaEntity() {
    }

    StoreJpaEntity(
        UUID id,
        String displayName,
        UUID catalogEntryId,
        StoreOnboardingStatus onboardingStatus,
        Instant createdAt
    ) {
        this.id = id;
        this.displayName = displayName;
        this.catalogEntryId = catalogEntryId;
        this.onboardingStatus = onboardingStatus;
        this.createdAt = createdAt;
    }

    UUID id() {
        return id;
    }

    String displayName() {
        return displayName;
    }

    UUID catalogEntryId() {
        return catalogEntryId;
    }

    StoreOnboardingStatus onboardingStatus() {
        return onboardingStatus;
    }

    Instant createdAt() {
        return createdAt;
    }
}
