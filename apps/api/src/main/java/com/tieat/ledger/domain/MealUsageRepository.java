package com.tieat.ledger.domain;

import java.util.Optional;
import com.tieat.store.domain.StoreId;

public interface MealUsageRepository {

    MealUsage save(MealUsage mealUsage);

    Optional<MealUsage> findById(MealUsageId id);

    MealUsageSlice findPendingByStoreId(StoreId storeId, int page, int size);
}
