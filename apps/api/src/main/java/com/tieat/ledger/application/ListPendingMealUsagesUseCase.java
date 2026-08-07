package com.tieat.ledger.application;

import com.tieat.ledger.domain.MealUsageRepository;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ListPendingMealUsagesUseCase {

    private final MealUsageRepository mealUsageRepository;

    public ListPendingMealUsagesUseCase(MealUsageRepository mealUsageRepository) {
        this.mealUsageRepository = Objects.requireNonNull(mealUsageRepository);
    }

    @Transactional(readOnly = true)
    public PendingMealUsagePage list(ListPendingMealUsagesQuery query) {
        Objects.requireNonNull(query, "Pending meal usage query must be supplied");
        var slice = mealUsageRepository.findPendingByStoreId(query.storeId(), query.page(), query.size());
        return new PendingMealUsagePage(slice.items(), query.page(), query.size(), slice.hasNext());
    }
}
