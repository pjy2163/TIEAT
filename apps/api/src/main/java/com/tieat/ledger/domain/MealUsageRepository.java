package com.tieat.ledger.domain;

import java.util.Optional;
import com.tieat.store.domain.StoreId;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.qr.domain.MealUsageQrContextId;
import java.time.Instant;

public interface MealUsageRepository {

    int MAX_PENDING_PER_STORE = 30;

    void lockStoreForPendingCreation(StoreId storeId);

    long countPendingByStoreId(StoreId storeId, Instant now);

    MealUsage save(MealUsage mealUsage);

    Optional<MealUsage> findById(MealUsageId id);

    Optional<MealUsage> findByIdForUpdate(MealUsageId id);

    MealUsageSlice findPendingByStoreId(StoreId storeId, Instant now, int page, int size);

    MonthlyMealUsageSlice findConfirmedByStoreIdAndCreatedAtBetween(
        StoreId storeId,
        Instant startInclusive,
        Instant endExclusive,
        int page,
        int size
    );

    MonthlyMealUsageSlice findConfirmedByStoreIdAndMealContractIdAndCreatedAtBetween(
        StoreId storeId,
        MealContractId mealContractId,
        Instant startInclusive,
        Instant endExclusive,
        int page,
        int size
    );

    long sumConfirmedByStoreIdAndCreatedAtBetween(
        StoreId storeId,
        Instant startInclusive,
        Instant endExclusive
    );

    long sumConfirmedByStoreIdAndMealContractIdAndCreatedAtBetween(
        StoreId storeId,
        MealContractId mealContractId,
        Instant startInclusive,
        Instant endExclusive
    );

    long countPublicQrCreatedSince(MealUsageQrContextId qrContextId, Instant since);

    int anonymizeCustomerNamesCreatedBefore(Instant cutoffExclusive, Instant executedAt);
}
