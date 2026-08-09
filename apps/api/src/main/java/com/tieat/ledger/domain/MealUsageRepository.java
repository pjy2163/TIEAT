package com.tieat.ledger.domain;

import java.util.Optional;
import com.tieat.store.domain.StoreId;
import com.tieat.qr.domain.MealUsageQrContextId;
import java.time.Instant;

public interface MealUsageRepository {

    MealUsage save(MealUsage mealUsage);

    Optional<MealUsage> findById(MealUsageId id);

    MealUsageSlice findPendingByStoreId(StoreId storeId, int page, int size);

    long countPublicQrCreatedSince(MealUsageQrContextId qrContextId, Instant since);
}
