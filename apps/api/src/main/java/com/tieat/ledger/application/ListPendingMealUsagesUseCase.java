package com.tieat.ledger.application;

import com.tieat.ledger.domain.MealUsageRepository;
import java.util.Objects;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ListPendingMealUsagesUseCase {

    private final MealUsageRepository mealUsageRepository;
    private final Clock clock;

    public ListPendingMealUsagesUseCase(MealUsageRepository mealUsageRepository, Clock clock) {
        this.mealUsageRepository = Objects.requireNonNull(mealUsageRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(readOnly = true)
    public PendingMealUsagePage list(ListPendingMealUsagesQuery query) {
        Objects.requireNonNull(query, "Pending meal usage query must be supplied");
        var slice = mealUsageRepository.findPendingByStoreId(query.storeId(), Instant.now(clock), query.page(), query.size());
        return new PendingMealUsagePage(slice.items(), query.page(), query.size(), slice.hasNext());
    }
}
