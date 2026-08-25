package com.tieat.settlement.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tieat.ledger.application.MealContractNotFoundException;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.settlement.domain.CumulativeSettlementSnapshot;
import com.tieat.settlement.domain.PosSettlementRepository;
import com.tieat.store.domain.StoreId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CumulativeSettlementExportServiceTest {

    private static final StoreId STORE_ID = new StoreId(UUID.fromString("9d5e37dd-dbe2-40dc-97fb-8e77c89aa4cb"));
    private static final MealContractId CONTRACT_ID = new MealContractId(
        UUID.fromString("f49a63ea-e09e-4ce6-8e36-e531521cdbcf")
    );
    private static final Instant GENERATED_AT = Instant.parse("2026-08-25T04:00:00Z");

    @Test
    void generatesTheWorkbookFromTheAuthenticatedStoreScopedSnapshot() {
        PosSettlementRepository repository = mock(PosSettlementRepository.class);
        CumulativeSettlementWorkbookGenerator generator = mock(CumulativeSettlementWorkbookGenerator.class);
        CumulativeSettlementSnapshot snapshot = snapshot();
        byte[] workbook = new byte[] { 80, 75, 3, 4 };
        when(repository.findCumulativeSettlementSnapshotByMealContractIdAndStoreId(CONTRACT_ID, STORE_ID, GENERATED_AT))
            .thenReturn(Optional.of(snapshot));
        when(generator.generate(snapshot)).thenReturn(workbook);
        CumulativeSettlementExportService service = new CumulativeSettlementExportService(
            repository,
            generator,
            Clock.fixed(GENERATED_AT, ZoneOffset.UTC)
        );

        assertThat(service.export(STORE_ID, CONTRACT_ID)).isEqualTo(workbook);

        verify(repository).findCumulativeSettlementSnapshotByMealContractIdAndStoreId(CONTRACT_ID, STORE_ID, GENERATED_AT);
        verify(generator).generate(snapshot);
    }

    @Test
    void reportsAContractOutsideTheAuthenticatedStoreAsNotFound() {
        PosSettlementRepository repository = mock(PosSettlementRepository.class);
        CumulativeSettlementWorkbookGenerator generator = mock(CumulativeSettlementWorkbookGenerator.class);
        when(repository.findCumulativeSettlementSnapshotByMealContractIdAndStoreId(CONTRACT_ID, STORE_ID, GENERATED_AT))
            .thenReturn(Optional.empty());
        CumulativeSettlementExportService service = new CumulativeSettlementExportService(
            repository,
            generator,
            Clock.fixed(GENERATED_AT, ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> service.export(STORE_ID, CONTRACT_ID))
            .isInstanceOf(MealContractNotFoundException.class);
    }

    private CumulativeSettlementSnapshot snapshot() {
        return new CumulativeSettlementSnapshot(
            CONTRACT_ID,
            GENERATED_AT,
            15_000,
            4_000,
            2_000,
            11_000,
            7_000,
            7_000
        );
    }
}
