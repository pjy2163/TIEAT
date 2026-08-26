package com.tieat.settlement.domain;

import com.tieat.partnership.domain.MealContractId;
import com.tieat.store.domain.StoreId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * An immutable attestation that a POS payment was already completed outside TIEAT.
 *
 * <p>The allocation amounts are snapshots of the confirmed receivables covered by that POS payment.
 * This record never represents a payment instruction or a prepaid-balance mutation.</p>
 */
public record PosSettlement(
    UUID id,
    StoreId storeId,
    MealContractId mealContractId,
    LocalDate posBusinessDate,
    long submittedTotalMinor,
    String recordedByLoginId,
    Instant recordedAt,
    UUID idempotencyKey,
    List<Allocation> allocations
) {

    public PosSettlement {
        Objects.requireNonNull(id, "POS settlement id must be supplied");
        Objects.requireNonNull(storeId, "Store id must be supplied");
        Objects.requireNonNull(mealContractId, "Meal contract id must be supplied");
        Objects.requireNonNull(posBusinessDate, "POS business date must be supplied");
        if (submittedTotalMinor <= 0) {
            throw new IllegalArgumentException("Submitted POS total must be positive");
        }
        if (recordedByLoginId == null || recordedByLoginId.isBlank()) {
            throw new IllegalArgumentException("Settlement recorder must be supplied");
        }
        Objects.requireNonNull(recordedAt, "Settlement recorded time must be supplied");
        Objects.requireNonNull(idempotencyKey, "Idempotency key must be supplied");
        Objects.requireNonNull(allocations, "Settlement allocations must be supplied");
        allocations = allocations.stream()
            .sorted(Comparator.comparing(Allocation::mealUsageId))
            .toList();
        if (allocations.isEmpty()) {
            throw new IllegalArgumentException("At least one receivable allocation must be supplied");
        }
        Set<UUID> allocationUsageIds = new HashSet<>();
        long allocatedTotal = 0;
        for (Allocation allocation : allocations) {
            if (!allocationUsageIds.add(allocation.mealUsageId())) {
                throw new IllegalArgumentException("Meal usage allocations must be unique");
            }
            allocatedTotal = Math.addExact(allocatedTotal, allocation.receivableAmountMinor());
        }
        if (allocatedTotal != submittedTotalMinor) {
            throw new IllegalArgumentException("Settlement allocations must equal the submitted POS total");
        }
    }

    public boolean matchesRequest(
        MealContractId requestedMealContractId,
        LocalDate requestedPosBusinessDate,
        long requestedSubmittedTotalMinor,
        List<UUID> requestedMealUsageIds
    ) {
        return mealContractId.equals(requestedMealContractId)
            && posBusinessDate.equals(requestedPosBusinessDate)
            && submittedTotalMinor == requestedSubmittedTotalMinor
            && allocationUsageIds().equals(List.copyOf(requestedMealUsageIds));
    }

    public List<UUID> allocationUsageIds() {
        return allocations.stream().map(Allocation::mealUsageId).toList();
    }

    public record Allocation(UUID mealUsageId, long receivableAmountMinor) {

        public Allocation {
            Objects.requireNonNull(mealUsageId, "Allocated meal usage id must be supplied");
            if (receivableAmountMinor <= 0) {
                throw new IllegalArgumentException("Allocated receivable amount must be positive");
            }
        }
    }
}
