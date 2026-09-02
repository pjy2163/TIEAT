package com.tieat.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.tieat.ledger.domain.EntrySource;
import com.tieat.ledger.domain.MealUsage;
import com.tieat.ledger.domain.MealUsageId;
import com.tieat.ledger.domain.MealUsageRepository;
import com.tieat.ledger.domain.MealUsageSlice;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.store.domain.StoreId;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MealUsagePendingListUseCaseTest {

    private static final StoreId STORE_ID = new StoreId(UUID.fromString("9d5e37dd-dbe2-40dc-97fb-8e77c89aa4cb"));

    @Test
    void returnsStoreScopedSliceMetadataAndKeepsEmptyResultsEmpty() {
        MealUsageRepository repository = mock(MealUsageRepository.class);
        MealUsage usage = pending("00000000-0000-0000-0000-000000000001");
        Instant now = Instant.parse("2026-08-06T01:00:00Z");
        given(repository.findPendingByStoreId(STORE_ID, now, 2, 50))
            .willReturn(new MealUsageSlice(List.of(usage), true));
        given(repository.findPendingByStoreId(STORE_ID, now, 0, 50))
            .willReturn(new MealUsageSlice(List.of(), false));
        ListPendingMealUsagesUseCase useCase = new ListPendingMealUsagesUseCase(
            repository, Clock.fixed(now, ZoneOffset.UTC)
        );

        PendingMealUsagePage result = useCase.list(new ListPendingMealUsagesQuery(STORE_ID, "PENDING", 2, 50));
        PendingMealUsagePage empty = useCase.list(new ListPendingMealUsagesQuery(STORE_ID, "PENDING", 0, 50));

        assertThat(result.items()).containsExactly(usage);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(50);
        assertThat(result.hasNext()).isTrue();
        assertThat(empty.items()).isEmpty();
        assertThat(empty.hasNext()).isFalse();
        verify(repository).findPendingByStoreId(STORE_ID, now, 2, 50);
        verify(repository).findPendingByStoreId(STORE_ID, now, 0, 50);
    }

    @Test
    void rejectsAnythingExceptBoundedPendingQueryBeforeRepositoryAccess() {
        for (Runnable invalidQuery : List.<Runnable>of(
            () -> new ListPendingMealUsagesQuery(STORE_ID, "CONFIRMED", 0, 50),
            () -> new ListPendingMealUsagesQuery(STORE_ID, "pending", 0, 50),
            () -> new ListPendingMealUsagesQuery(STORE_ID, "PENDING", -1, 50),
            () -> new ListPendingMealUsagesQuery(STORE_ID, "PENDING", 0, 0),
            () -> new ListPendingMealUsagesQuery(STORE_ID, "PENDING", 0, 101)
        )) {
            assertThatThrownBy(invalidQuery::run).isInstanceOf(InvalidPendingMealUsageQueryException.class);
        }
        assertThat(new ListPendingMealUsagesQuery(STORE_ID, "PENDING", 0, 1).size()).isOne();
        assertThat(new ListPendingMealUsagesQuery(STORE_ID, "PENDING", 0, 100).size()).isEqualTo(100);
    }

    private MealUsage pending(String id) {
        return MealUsage.pending(
            new MealUsageId(UUID.fromString(id)),
            STORE_ID,
            new MealContractId(UUID.fromString("8cb73a47-d5c5-4f7a-8db0-b61e171c4f0a")),
            EntrySource.STORE_TABLET,
            12_000,
            Instant.parse("2026-08-06T01:00:00Z")
        );
    }
}
