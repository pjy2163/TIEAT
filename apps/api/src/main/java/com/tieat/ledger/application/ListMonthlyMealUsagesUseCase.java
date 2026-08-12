package com.tieat.ledger.application;

import com.tieat.ledger.domain.MealUsageRepository;
import java.time.ZoneId;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ListMonthlyMealUsagesUseCase {

    private static final ZoneId KOREA_STANDARD_TIME = ZoneId.of("Asia/Seoul");

    private final MealUsageRepository mealUsageRepository;

    public ListMonthlyMealUsagesUseCase(MealUsageRepository mealUsageRepository) {
        this.mealUsageRepository = Objects.requireNonNull(mealUsageRepository);
    }

    @Transactional(readOnly = true)
    public MonthlyMealUsagePage list(ListMonthlyMealUsagesQuery query) {
        Objects.requireNonNull(query, "Monthly meal usage query must be supplied");
        var month = query.yearMonth();
        var monthStart = month.atDay(1).atStartOfDay(KOREA_STANDARD_TIME).toInstant();
        var nextMonthStart = month.plusMonths(1).atDay(1).atStartOfDay(KOREA_STANDARD_TIME).toInstant();
        var slice = mealUsageRepository.findConfirmedByStoreIdAndCreatedAtBetween(
            query.storeId(), monthStart, nextMonthStart, query.page(), query.size()
        );
        return new MonthlyMealUsagePage(query.month(), slice.items(), query.page(), query.size(), slice.hasNext());
    }
}
