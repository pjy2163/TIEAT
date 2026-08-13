package com.tieat.settlement.domain;

import com.tieat.ledger.domain.MealUsageStatus;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.store.domain.StoreId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface PosSettlementRepository {

    void lockStoreForSettlement(StoreId storeId);

    Optional<PosSettlement> findByStoreIdAndIdempotencyKey(StoreId storeId, UUID idempotencyKey);

    boolean existsMealContractByIdAndStoreId(MealContractId mealContractId, StoreId storeId);

    List<LockedReceivable> lockReceivablesByIdAndStoreId(List<UUID> mealUsageIds, StoreId storeId);

    List<OutstandingReceivable> findOutstandingReceivablesByStoreId(StoreId storeId);

    PosSettlementSlice findByStoreId(StoreId storeId, int page, int size);

    List<AllocationDisplay> findAllocationDisplaysBySettlementIdAndStoreId(
        UUID posSettlementId,
        StoreId storeId
    );

    void insert(PosSettlement settlement);

    record LockedReceivable(
        UUID mealUsageId,
        MealContractId mealContractId,
        MealUsageStatus status,
        Long receivableCreatedMinor,
        boolean alreadyAllocated
    ) {

        public LockedReceivable {
            Objects.requireNonNull(mealUsageId, "Meal usage id must be supplied");
            Objects.requireNonNull(mealContractId, "Meal contract id must be supplied");
            Objects.requireNonNull(status, "Meal usage status must be supplied");
        }
    }

    record OutstandingReceivable(
        UUID mealUsageId,
        MealContractId mealContractId,
        String partnerDisplayName,
        Instant confirmedAt,
        long receivableCreatedMinor
    ) {

        public OutstandingReceivable {
            Objects.requireNonNull(mealUsageId, "Meal usage id must be supplied");
            Objects.requireNonNull(mealContractId, "Meal contract id must be supplied");
            if (receivableCreatedMinor <= 0) {
                throw new IllegalArgumentException("Receivable amount must be positive");
            }
            Objects.requireNonNull(confirmedAt, "Confirmation time must be supplied");
        }
    }

    record AllocationDisplay(
        UUID mealUsageId,
        String partnerDisplayName,
        Instant confirmedAt,
        long receivableAmountMinor
    ) {

        public AllocationDisplay {
            Objects.requireNonNull(mealUsageId, "Meal usage id must be supplied");
            Objects.requireNonNull(confirmedAt, "Confirmation time must be supplied");
            if (receivableAmountMinor <= 0) {
                throw new IllegalArgumentException("Receivable amount must be positive");
            }
        }
    }
}
