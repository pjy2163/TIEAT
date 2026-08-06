package com.tieat.ledger.domain;

import com.tieat.partnership.domain.MealContractId;
import com.tieat.store.domain.StoreId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class MealUsage {

    private final MealUsageId id;
    private final StoreId storeId;
    private final MealContractId mealContractId;
    private final EntrySource entrySource;
    private final long amount;
    private final Instant createdAt;
    private final long version;
    private MealUsageStatus status;
    private Confirmation confirmation;
    private PrepaidAllocation prepaidAllocation;

    private MealUsage(
        MealUsageId id,
        StoreId storeId,
        MealContractId mealContractId,
        EntrySource entrySource,
        long amount,
        Instant createdAt,
        long version,
        MealUsageStatus status,
        Confirmation confirmation,
        PrepaidAllocation prepaidAllocation
    ) {
        this.id = Objects.requireNonNull(id, "Meal usage id must be supplied");
        this.storeId = Objects.requireNonNull(storeId, "Store id must be supplied");
        this.mealContractId = Objects.requireNonNull(mealContractId, "Meal contract id must be supplied");
        this.entrySource = Objects.requireNonNull(entrySource, "Entry source must be supplied");
        if (amount <= 0) {
            throw new IllegalArgumentException("Usage amount must be positive");
        }
        this.amount = amount;
        this.createdAt = Objects.requireNonNull(createdAt, "Created at must be supplied");
        if (version < 0) {
            throw new IllegalArgumentException("Meal usage version must not be negative");
        }
        this.version = version;
        this.status = Objects.requireNonNull(status, "Meal usage status must be supplied");
        this.confirmation = confirmation;
        this.prepaidAllocation = prepaidAllocation;
        validateLifecycleState();
    }

    public static MealUsage pending(
        MealUsageId id,
        StoreId storeId,
        MealContractId mealContractId,
        EntrySource entrySource,
        long amount,
        Instant createdAt
    ) {
        return new MealUsage(
            id,
            storeId,
            mealContractId,
            entrySource,
            amount,
            createdAt,
            0,
            MealUsageStatus.PENDING,
            null,
            null
        );
    }

    public static MealUsage restorePending(
        MealUsageId id,
        StoreId storeId,
        MealContractId mealContractId,
        EntrySource entrySource,
        long amount,
        Instant createdAt,
        long version
    ) {
        return new MealUsage(
            id,
            storeId,
            mealContractId,
            entrySource,
            amount,
            createdAt,
            version,
            MealUsageStatus.PENDING,
            null,
            null
        );
    }

    public static MealUsage restoreConfirmed(
        MealUsageId id,
        StoreId storeId,
        MealContractId mealContractId,
        EntrySource entrySource,
        long amount,
        Instant createdAt,
        long version,
        Confirmation confirmation,
        PrepaidAllocation prepaidAllocation
    ) {
        return new MealUsage(
            id,
            storeId,
            mealContractId,
            entrySource,
            amount,
            createdAt,
            version,
            MealUsageStatus.CONFIRMED,
            confirmation,
            prepaidAllocation
        );
    }

    public void confirm(String staffInitials, Instant confirmedAt, PrepaidAllocation allocation) {
        if (status != MealUsageStatus.PENDING) {
            throw new IllegalStateException("Only pending meal usages can be confirmed");
        }

        Confirmation nextConfirmation = new Confirmation(staffInitials, confirmedAt);
        PrepaidAllocation nextAllocation = Objects.requireNonNull(allocation, "Prepaid allocation must be supplied");
        if (nextAllocation.usageAmount() != amount) {
            throw new IllegalArgumentException("Prepaid allocation amount must match meal usage amount");
        }

        this.confirmation = nextConfirmation;
        this.prepaidAllocation = nextAllocation;
        this.status = MealUsageStatus.CONFIRMED;
    }

    public EntrySource entrySource() {
        return entrySource;
    }

    public MealUsageId id() {
        return id;
    }

    public StoreId storeId() {
        return storeId;
    }

    public MealContractId mealContractId() {
        return mealContractId;
    }

    public long amount() {
        return amount;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public long version() {
        return version;
    }

    public MealUsageStatus status() {
        return status;
    }

    public Optional<Confirmation> confirmation() {
        return Optional.ofNullable(confirmation);
    }

    public Optional<PrepaidAllocation> prepaidAllocation() {
        return Optional.ofNullable(prepaidAllocation);
    }

    private void validateLifecycleState() {
        if (status == MealUsageStatus.PENDING && (confirmation != null || prepaidAllocation != null)) {
            throw new IllegalArgumentException("Pending meal usages must not have confirmation data");
        }
        if (status == MealUsageStatus.CONFIRMED && (confirmation == null || prepaidAllocation == null)) {
            throw new IllegalArgumentException("Confirmed meal usages require confirmation and allocation data");
        }
        if (prepaidAllocation != null && prepaidAllocation.usageAmount() != amount) {
            throw new IllegalArgumentException("Prepaid allocation amount must match meal usage amount");
        }
    }
}
