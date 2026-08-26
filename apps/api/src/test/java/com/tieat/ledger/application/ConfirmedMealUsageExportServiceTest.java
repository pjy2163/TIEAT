package com.tieat.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tieat.ledger.domain.EntrySource;
import com.tieat.ledger.domain.MealUsage;
import com.tieat.ledger.domain.MealUsageId;
import com.tieat.ledger.domain.MonthlyMealUsageRow;
import com.tieat.ledger.domain.PrepaidAllocation;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.store.domain.StoreId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConfirmedMealUsageExportServiceTest {

    private static final StoreId STORE_ID = new StoreId(UUID.fromString("9d5e37dd-dbe2-40dc-97fb-8e77c89aa4cb"));
    private static final MealContractId CONTRACT_ID = new MealContractId(
        UUID.fromString("8cb73a47-d5c5-4f7a-8db0-b61e171c4f0a")
    );
    private static final Instant GENERATED_AT = Instant.parse("2026-08-26T04:00:00Z");

    @Test
    void collectsAllPagesThroughTheExistingStoreAndContractScopedQueryBeforeGenerating() {
        ListMonthlyMealUsagesUseCase listUseCase = mock(ListMonthlyMealUsagesUseCase.class);
        ConfirmedMealUsageWorkbookGenerator generator = mock(ConfirmedMealUsageWorkbookGenerator.class);
        MealUsage usage = confirmedUsage("00000000-0000-0000-0000-000000000001");
        when(listUseCase.listConfirmed(any())).thenReturn(new ConfirmedMealUsagePage(
            "2026-08-01", "2026-08-31", List.of(new MonthlyMealUsageRow(usage, false)), 0, 100, false, 12_000
        ));
        byte[] workbook = new byte[] { 80, 75, 3, 4 };
        when(generator.generate(any())).thenReturn(workbook);
        ConfirmedMealUsageExportService service = new ConfirmedMealUsageExportService(
            listUseCase,
            generator,
            Clock.fixed(GENERATED_AT, ZoneOffset.UTC)
        );

        assertThat(service.export(STORE_ID, "2026-08-01", "2026-08-31", CONTRACT_ID)).isEqualTo(workbook);

        ArgumentCaptor<ListConfirmedMealUsagesQuery> queryCaptor = ArgumentCaptor.forClass(ListConfirmedMealUsagesQuery.class);
        verify(listUseCase).listConfirmed(queryCaptor.capture());
        assertThat(queryCaptor.getValue().storeId()).isEqualTo(STORE_ID);
        assertThat(queryCaptor.getValue().fromDate()).isEqualTo("2026-08-01");
        assertThat(queryCaptor.getValue().toDate()).isEqualTo("2026-08-31");
        assertThat(queryCaptor.getValue().page()).isZero();
        assertThat(queryCaptor.getValue().size()).isEqualTo(100);
        assertThat(queryCaptor.getValue().mealContractId()).isEqualTo(CONTRACT_ID);

        ArgumentCaptor<ConfirmedMealUsageExportSnapshot> snapshotCaptor = ArgumentCaptor.forClass(ConfirmedMealUsageExportSnapshot.class);
        verify(generator).generate(snapshotCaptor.capture());
        assertThat(snapshotCaptor.getValue().scopeLabel()).isEqualTo("선택한 협력사·계약");
        assertThat(snapshotCaptor.getValue().generatedAt()).isEqualTo(GENERATED_AT);
        assertThat(snapshotCaptor.getValue().rows()).hasSize(1);
        assertThat(snapshotCaptor.getValue().rows().getFirst().paymentStatus()).isEqualTo("결제 전");
    }

    @Test
    void rejectsAQueryBeyondTheExisting366DayRuleBeforeReadingData() {
        ListMonthlyMealUsagesUseCase listUseCase = mock(ListMonthlyMealUsagesUseCase.class);
        ConfirmedMealUsageWorkbookGenerator generator = mock(ConfirmedMealUsageWorkbookGenerator.class);
        ConfirmedMealUsageExportService service = new ConfirmedMealUsageExportService(
            listUseCase,
            generator,
            Clock.fixed(GENERATED_AT, ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> service.export(STORE_ID, "2026-01-01", "2027-01-02", null))
            .isInstanceOf(InvalidConfirmedMealUsageQueryException.class);
        verify(listUseCase, never()).listConfirmed(any());
        verify(generator, never()).generate(any());
    }

    @Test
    void stopsWithoutGeneratingAPartialWorkbookWhenTheRowLimitIsExceeded() {
        ListMonthlyMealUsagesUseCase listUseCase = mock(ListMonthlyMealUsagesUseCase.class);
        ConfirmedMealUsageWorkbookGenerator generator = mock(ConfirmedMealUsageWorkbookGenerator.class);
        MonthlyMealUsageRow row = new MonthlyMealUsageRow(confirmedUsage("00000000-0000-0000-0000-000000000002"), false);
        when(listUseCase.listConfirmed(any())).thenAnswer(invocation -> {
            ListConfirmedMealUsagesQuery query = invocation.getArgument(0);
            if (query.page() < 100) {
                return new ConfirmedMealUsagePage(
                    "2026-08-01", "2026-08-31", java.util.Collections.nCopies(100, row), query.page(), 100, true, 1_200_000
                );
            }
            return new ConfirmedMealUsagePage(
                "2026-08-01", "2026-08-31", List.of(row), query.page(), 100, false, 1_200_000
            );
        });
        ConfirmedMealUsageExportService service = new ConfirmedMealUsageExportService(
            listUseCase,
            generator,
            Clock.fixed(GENERATED_AT, ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> service.export(STORE_ID, "2026-08-01", "2026-08-31", null))
            .isInstanceOf(ConfirmedMealUsageExportTooLargeException.class)
            .hasMessage("Confirmed meal usage export exceeds 10000 rows");
        verify(generator, never()).generate(any());
        verify(listUseCase, org.mockito.Mockito.times(101)).listConfirmed(any());
    }

    private MealUsage confirmedUsage(String id) {
        MealUsage usage = MealUsage.pending(
            new MealUsageId(UUID.fromString(id)),
            STORE_ID,
            CONTRACT_ID,
            EntrySource.STORE_TABLET,
            12_000,
            Instant.parse("2026-08-05T01:00:00Z")
        );
        usage.confirm(
            "HK",
            Instant.parse("2026-08-05T02:00:00Z"),
            new PrepaidAllocation(12_000, 0, 12_000, 0)
        );
        return usage;
    }
}
