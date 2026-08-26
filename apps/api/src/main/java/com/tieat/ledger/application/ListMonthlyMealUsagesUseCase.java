package com.tieat.ledger.application;

import com.tieat.ledger.domain.MealUsageRepository;
import com.tieat.partnership.application.StorePartnerService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ListMonthlyMealUsagesUseCase {

    private static final ZoneId KOREA_STANDARD_TIME = ZoneId.of("Asia/Seoul");

    private final MealUsageRepository mealUsageRepository;
    private final StorePartnerService storePartnerService;

    public ListMonthlyMealUsagesUseCase(
        MealUsageRepository mealUsageRepository,
        StorePartnerService storePartnerService
    ) {
        this.mealUsageRepository = Objects.requireNonNull(mealUsageRepository);
        this.storePartnerService = Objects.requireNonNull(storePartnerService);
    }

    @Transactional(readOnly = true)
    public MonthlyMealUsagePage list(ListMonthlyMealUsagesQuery query) {
        Objects.requireNonNull(query, "Monthly meal usage query must be supplied");
        var fromMonth = query.fromYearMonth();
        var toMonth = query.toYearMonth();
        var monthStart = fromMonth.atDay(1).atStartOfDay(KOREA_STANDARD_TIME).toInstant();
        var nextMonthStart = toMonth.plusMonths(1).atDay(1).atStartOfDay(KOREA_STANDARD_TIME).toInstant();
        var mealContractId = query.mealContractId();
        if (mealContractId != null) {
            storePartnerService.requireOwnedPartner(query.storeId(), mealContractId);
        }
        var slice = mealContractId == null
            ? mealUsageRepository.findConfirmedByStoreIdAndCreatedAtBetween(
                query.storeId(), monthStart, nextMonthStart, query.page(), query.size()
            )
            : filteredSlice(query, monthStart, nextMonthStart);
        long totalAmountMinor = mealContractId == null
            ? mealUsageRepository.sumConfirmedByStoreIdAndCreatedAtBetween(
                query.storeId(), monthStart, nextMonthStart
            )
            : filteredTotal(query, monthStart, nextMonthStart);
        return new MonthlyMealUsagePage(
            query.fromMonth(),
            query.fromMonth(),
            query.toMonth(),
            slice.items(),
            query.page(),
            query.size(),
            slice.hasNext(),
            totalAmountMinor
        );
    }

    @Transactional(readOnly = true)
    public ConfirmedMealUsagePage listConfirmed(ListConfirmedMealUsagesQuery query) {
        Objects.requireNonNull(query, "Confirmed meal usage query must be supplied");
        InstantRange range = range(query.fromLocalDate(), query.toLocalDate());
        var mealContractId = query.mealContractId();
        if (mealContractId != null) {
            storePartnerService.requireOwnedPartner(query.storeId(), mealContractId);
        }
        var slice = mealContractId == null
            ? mealUsageRepository.findConfirmedByStoreIdAndCreatedAtBetween(
                query.storeId(), range.startInclusive(), range.endExclusive(), query.page(), query.size()
            )
            : mealUsageRepository.findConfirmedByStoreIdAndMealContractIdAndCreatedAtBetween(
                query.storeId(), mealContractId, range.startInclusive(), range.endExclusive(), query.page(), query.size()
            );
        long totalAmountMinor = mealContractId == null
            ? mealUsageRepository.sumConfirmedByStoreIdAndCreatedAtBetween(
                query.storeId(), range.startInclusive(), range.endExclusive()
            )
            : mealUsageRepository.sumConfirmedByStoreIdAndMealContractIdAndCreatedAtBetween(
                query.storeId(), mealContractId, range.startInclusive(), range.endExclusive()
            );
        return new ConfirmedMealUsagePage(
            query.fromDate(),
            query.toDate(),
            slice.items(),
            query.page(),
            query.size(),
            slice.hasNext(),
            totalAmountMinor
        );
    }

    private InstantRange range(LocalDate fromDate, LocalDate toDate) {
        return new InstantRange(
            fromDate.atStartOfDay(KOREA_STANDARD_TIME).toInstant(),
            toDate.plusDays(1).atStartOfDay(KOREA_STANDARD_TIME).toInstant()
        );
    }

    private record InstantRange(java.time.Instant startInclusive, java.time.Instant endExclusive) {
    }

    private com.tieat.ledger.domain.MonthlyMealUsageSlice filteredSlice(
        ListMonthlyMealUsagesQuery query,
        java.time.Instant monthStart,
        java.time.Instant nextMonthStart
    ) {
        return mealUsageRepository.findConfirmedByStoreIdAndMealContractIdAndCreatedAtBetween(
            query.storeId(), query.mealContractId(), monthStart, nextMonthStart, query.page(), query.size()
        );
    }

    private long filteredTotal(
        ListMonthlyMealUsagesQuery query,
        java.time.Instant monthStart,
        java.time.Instant nextMonthStart
    ) {
        return mealUsageRepository.sumConfirmedByStoreIdAndMealContractIdAndCreatedAtBetween(
            query.storeId(), query.mealContractId(), monthStart, nextMonthStart
        );
    }
}
