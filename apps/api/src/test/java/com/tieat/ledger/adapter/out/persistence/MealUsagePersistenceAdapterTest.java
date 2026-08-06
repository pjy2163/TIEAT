package com.tieat.ledger.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.tieat.ledger.domain.EntrySource;
import com.tieat.ledger.domain.MealUsage;
import com.tieat.ledger.domain.MealUsageId;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.store.domain.StoreId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MealUsagePersistenceAdapterTest {

    @Test
    void rejectsConfirmedMealUsageBeforeCallingJpaRepository() {
        MealUsageJpaRepository jpaRepository = mock(MealUsageJpaRepository.class);
        MealUsagePersistenceAdapter adapter = new MealUsagePersistenceAdapter(jpaRepository);
        MealUsage confirmed = MealUsage.pending(
            new MealUsageId(UUID.fromString("7693bfcf-01b4-4de2-9c9f-5d81612460e5")),
            new StoreId(UUID.fromString("9d5e37dd-dbe2-40dc-97fb-8e77c89aa4cb")),
            new MealContractId(UUID.fromString("019c0f9c-6d58-7d37-b0e3-1af21f7124b9")),
            EntrySource.STORE_TABLET,
            12_000,
            Instant.parse("2026-08-05T09:14:30Z")
        );
        confirmed.confirm("HK", Instant.parse("2026-08-05T09:15:30Z"), 12_000);

        assertThatThrownBy(() -> adapter.save(confirmed))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessage("Only pending meal usages can be persisted in the initial schema");

        verifyNoInteractions(jpaRepository);
    }
}
