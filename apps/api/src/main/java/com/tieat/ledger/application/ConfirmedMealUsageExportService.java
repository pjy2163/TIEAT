package com.tieat.ledger.application;

import com.tieat.ledger.domain.MealUsage;
import com.tieat.ledger.domain.MealUsageStatus;
import com.tieat.ledger.domain.MonthlyMealUsageRow;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.store.domain.StoreId;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConfirmedMealUsageExportService {

    public static final int MAX_EXPORT_ROWS = 10_000;

    private static final int PAGE_SIZE = 100;

    private final ListMonthlyMealUsagesUseCase listMonthlyMealUsagesUseCase;
    private final ConfirmedMealUsageWorkbookGenerator workbookGenerator;
    private final Clock clock;

    public ConfirmedMealUsageExportService(
        ListMonthlyMealUsagesUseCase listMonthlyMealUsagesUseCase,
        ConfirmedMealUsageWorkbookGenerator workbookGenerator,
        Clock clock
    ) {
        this.listMonthlyMealUsagesUseCase = Objects.requireNonNull(listMonthlyMealUsagesUseCase);
        this.workbookGenerator = Objects.requireNonNull(workbookGenerator);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(readOnly = true)
    public byte[] export(
        StoreId storeId,
        String fromDate,
        String toDate,
        MealContractId mealContractId
    ) {
        Objects.requireNonNull(storeId, "Store id must be supplied");
        List<ConfirmedMealUsageExportSnapshot.Row> rows = new ArrayList<>();
        long totalAmountMinor = 0;
        for (int page = 0; ; page++) {
            ConfirmedMealUsagePage result = listMonthlyMealUsagesUseCase.listConfirmed(
                new ListConfirmedMealUsagesQuery(storeId, fromDate, toDate, page, PAGE_SIZE, mealContractId)
            );
            if (rows.size() + result.items().size() > MAX_EXPORT_ROWS) {
                throw new ConfirmedMealUsageExportTooLargeException();
            }
            rows.addAll(result.items().stream().map(this::toExportRow).toList());
            totalAmountMinor = result.totalAmountMinor();
            if (!result.hasNext()) {
                break;
            }
        }

        String scopeLabel = mealContractId == null ? "전체 협력사·계약" : "선택한 협력사·계약";
        return workbookGenerator.generate(new ConfirmedMealUsageExportSnapshot(
            fromDate,
            toDate,
            scopeLabel,
            Instant.now(clock),
            rows,
            totalAmountMinor
        ));
    }

    private ConfirmedMealUsageExportSnapshot.Row toExportRow(MonthlyMealUsageRow row) {
        MealUsage mealUsage = row.mealUsage();
        if (mealUsage.status() != MealUsageStatus.CONFIRMED) {
            throw new IllegalStateException("Confirmed meal usage export accepts confirmed usages only");
        }
        var confirmation = mealUsage.confirmation()
            .orElseThrow(() -> new IllegalStateException("Confirmed meal usage requires confirmation data"));
        return new ConfirmedMealUsageExportSnapshot.Row(
            mealUsage.partnerDisplayNameSnapshot().orElse(null),
            mealUsage.createdAt(),
            confirmation.confirmedAt(),
            mealUsage.amount(),
            paymentStatus(mealUsage, row.settlementAllocationExists())
        );
    }

    private String paymentStatus(MealUsage mealUsage, boolean allocationExists) {
        var allocation = mealUsage.prepaidAllocation()
            .orElseThrow(() -> new IllegalStateException("Confirmed meal usage requires allocation data"));
        if (allocation.receivableCreated() > 0) {
            return allocationExists ? "결제 완료" : "결제 전";
        }
        if (allocation.prepaidApplied() > 0) {
            return "결제 완료(선불)";
        }
        return null;
    }
}
