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
    private MealUsageStatus status;
    private Confirmation confirmation;
    private PrepaidAllocation prepaidAllocation;

    private MealUsage(
        MealUsageId id,
        StoreId storeId,
        MealContractId mealContractId,
        EntrySource entrySource,
        long amount,
        Instant createdAt
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
        this.status = MealUsageStatus.PENDING;
    }

    public static MealUsage pending(
        MealUsageId id,
        StoreId storeId,
        MealContractId mealContractId,
        EntrySource entrySource,
        long amount,
        Instant createdAt
    ) {
        return new MealUsage(id, storeId, mealContractId, entrySource, amount, createdAt);
    }

    /**
     * Records server-supplied confirmation audit data and fixes the allocation against the
     * prepaid balance available at this moment.
     */
    public void confirm(String staffInitials, Instant confirmedAt, long availablePrepaid) {
        if (status != MealUsageStatus.PENDING) {
            throw new IllegalStateException("Only pending meal usages can be confirmed");
        }

        Confirmation nextConfirmation = new Confirmation(staffInitials, confirmedAt);
        PrepaidAllocation nextAllocation = PrepaidAllocation.forUsage(amount, availablePrepaid);

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

    public MealUsageStatus status() {
        return status;
    }

    public Optional<Confirmation> confirmation() {
        return Optional.ofNullable(confirmation);
    }

    public Optional<PrepaidAllocation> prepaidAllocation() {
        return Optional.ofNullable(prepaidAllocation);
    }
}
