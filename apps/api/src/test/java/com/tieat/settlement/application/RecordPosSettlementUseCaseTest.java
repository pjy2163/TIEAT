package com.tieat.settlement.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tieat.ledger.domain.MealUsageStatus;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.settlement.domain.PosSettlement;
import com.tieat.settlement.domain.PosSettlementRepository;
import com.tieat.settlement.domain.PosSettlementSlice;
import com.tieat.store.domain.StoreId;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecordPosSettlementUseCaseTest {

    private static final StoreId STORE_ID = new StoreId(UUID.fromString("9d5e37dd-dbe2-40dc-97fb-8e77c89aa4cb"));
    private static final MealContractId CONTRACT_ID = new MealContractId(
        UUID.fromString("f49a63ea-e09e-4ce6-8e36-e531521cdbcf")
    );
    private static final Instant RECORDED_AT = Instant.parse("2026-08-12T03:45:00Z");

    @Test
    void recordsOnlyAnImmutableAttestationAndExactReceivableSnapshots() {
        UUID firstUsageId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID secondUsageId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        FakeRepository repository = FakeRepository.withLockedReceivables(
            locked(secondUsageId, 2_000),
            locked(firstUsageId, 1_000)
        );
        RecordPosSettlementUseCase useCase = new RecordPosSettlementUseCase(
            repository,
            Clock.fixed(RECORDED_AT, ZoneOffset.UTC)
        );

        PosSettlement recorded = useCase.record(command(UUID.randomUUID(), List.of(secondUsageId, firstUsageId), 3_000));

        assertThat(recorded.storeId()).isEqualTo(STORE_ID);
        assertThat(recorded.mealContractId()).isEqualTo(CONTRACT_ID);
        assertThat(recorded.recordedByLoginId()).isEqualTo("store-hk");
        assertThat(recorded.recordedAt()).isEqualTo(RECORDED_AT);
        assertThat(recorded.posBusinessDate()).isEqualTo(LocalDate.of(2026, 8, 11));
        assertThat(recorded.submittedTotalMinor()).isEqualTo(3_000);
        assertThat(recorded.allocations()).extracting(PosSettlement.Allocation::mealUsageId)
            .containsExactly(firstUsageId, secondUsageId);
        assertThat(recorded.allocations()).extracting(PosSettlement.Allocation::receivableAmountMinor)
            .containsExactly(1_000L, 2_000L);
        assertThat(repository.storeLockCalls).containsExactly(STORE_ID);
        assertThat(repository.insertedSettlements).containsExactly(recorded);
    }

    @Test
    void returnsTheOriginalSettlementForCanonicalExactReplayAndRejectsPayloadMismatch() {
        UUID firstUsageId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID secondUsageId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID idempotencyKey = UUID.randomUUID();
        FakeRepository repository = FakeRepository.withLockedReceivables(locked(firstUsageId, 1_000), locked(secondUsageId, 2_000));
        RecordPosSettlementUseCase useCase = new RecordPosSettlementUseCase(
            repository,
            Clock.fixed(RECORDED_AT, ZoneOffset.UTC)
        );

        PosSettlement first = useCase.record(command(idempotencyKey, List.of(secondUsageId, firstUsageId), 3_000));
        PosSettlement replay = useCase.record(command(idempotencyKey, List.of(firstUsageId, secondUsageId), 3_000));

        assertThat(replay).isEqualTo(first);
        assertThat(repository.insertedSettlements).hasSize(1);
        assertThatThrownBy(() -> useCase.record(command(idempotencyKey, List.of(firstUsageId, secondUsageId), 2_999)))
            .isInstanceOf(PosSettlementConflictException.class)
            .extracting(exception -> ((PosSettlementConflictException) exception).reason())
            .isEqualTo(PosSettlementConflictException.Reason.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void rejectsAUsageThatIsNotConfirmedReceivableBeforeItWritesAnything() {
        UUID usageId = UUID.randomUUID();
        FakeRepository repository = FakeRepository.withLockedReceivables(new PosSettlementRepository.LockedReceivable(
            usageId, CONTRACT_ID, MealUsageStatus.PENDING, null, false
        ));
        RecordPosSettlementUseCase useCase = new RecordPosSettlementUseCase(
            repository,
            Clock.fixed(RECORDED_AT, ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> useCase.record(command(UUID.randomUUID(), List.of(usageId), 1_000)))
            .isInstanceOf(PosSettlementConflictException.class)
            .extracting(exception -> ((PosSettlementConflictException) exception).reason())
            .isEqualTo(PosSettlementConflictException.Reason.USAGE_NOT_OUTSTANDING);
        assertThat(repository.insertedSettlements).isEmpty();
    }

    @Test
    void rejectsDuplicateUsageIdsAtTheCommandBoundary() {
        UUID usageId = UUID.randomUUID();

        assertThatThrownBy(() -> command(UUID.randomUUID(), List.of(usageId, usageId), 2_000))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unique");
    }

    @Test
    void returnsARepositorySuppliedHeaderPageWithoutChangingItsCanonicalSettlementSnapshots() {
        UUID usageId = UUID.randomUUID();
        PosSettlement existing = new PosSettlement(
            UUID.randomUUID(),
            STORE_ID,
            CONTRACT_ID,
            LocalDate.of(2026, 8, 11),
            1_000,
            "store-hk",
            RECORDED_AT,
            UUID.randomUUID(),
            List.of(new PosSettlement.Allocation(usageId, 1_000))
        );
        FakeRepository repository = FakeRepository.withLockedReceivables();
        repository.historySlice = new PosSettlementSlice(List.of(existing), true);
        RecordPosSettlementUseCase useCase = new RecordPosSettlementUseCase(
            repository,
            Clock.fixed(RECORDED_AT, ZoneOffset.UTC)
        );

        PosSettlementPage page = useCase.listSettlements(new ListPosSettlementsQuery(STORE_ID, 2, 50));

        assertThat(page.items()).containsExactly(existing);
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.size()).isEqualTo(50);
        assertThat(page.hasNext()).isTrue();
    }

    private RecordPosSettlementCommand command(UUID idempotencyKey, List<UUID> mealUsageIds, long total) {
        return new RecordPosSettlementCommand(
            STORE_ID,
            "store-hk",
            idempotencyKey,
            CONTRACT_ID,
            LocalDate.of(2026, 8, 11),
            total,
            mealUsageIds
        );
    }

    private PosSettlementRepository.LockedReceivable locked(UUID mealUsageId, long receivableCreatedMinor) {
        return new PosSettlementRepository.LockedReceivable(
            mealUsageId,
            CONTRACT_ID,
            MealUsageStatus.CONFIRMED,
            receivableCreatedMinor,
            false
        );
    }

    private static final class FakeRepository implements PosSettlementRepository {

        private final List<LockedReceivable> lockedReceivables;
        private final List<StoreId> storeLockCalls = new ArrayList<>();
        private final List<PosSettlement> insertedSettlements = new ArrayList<>();
        private final Map<UUID, PosSettlement> settlementsByIdempotencyKey = new HashMap<>();
        private PosSettlementSlice historySlice = new PosSettlementSlice(List.of(), false);

        private FakeRepository(List<LockedReceivable> lockedReceivables) {
            this.lockedReceivables = lockedReceivables.stream()
                .sorted(Comparator.comparing(LockedReceivable::mealUsageId))
                .toList();
        }

        static FakeRepository withLockedReceivables(LockedReceivable... receivables) {
            return new FakeRepository(List.of(receivables));
        }

        @Override
        public void lockStoreForSettlement(StoreId storeId) {
            storeLockCalls.add(storeId);
        }

        @Override
        public Optional<PosSettlement> findByStoreIdAndIdempotencyKey(StoreId storeId, UUID idempotencyKey) {
            return Optional.ofNullable(settlementsByIdempotencyKey.get(idempotencyKey));
        }

        @Override
        public boolean existsMealContractByIdAndStoreId(
            MealContractId mealContractId,
            StoreId storeId
        ) {
            return mealContractId.equals(CONTRACT_ID) && storeId.equals(STORE_ID);
        }

        @Override
        public List<LockedReceivable> lockReceivablesByIdAndStoreId(List<UUID> mealUsageIds, StoreId storeId) {
            if (!storeId.equals(STORE_ID)) {
                return List.of();
            }
            return lockedReceivables.stream().filter(receivable -> mealUsageIds.contains(receivable.mealUsageId())).toList();
        }

        @Override
        public OutstandingReceivableOverview findOutstandingReceivableOverviewByStoreId(StoreId storeId) {
            return new OutstandingReceivableOverview(List.of(), List.of());
        }

        @Override
        public PosSettlementSlice findByStoreId(StoreId storeId, int page, int size) {
            return historySlice;
        }

        @Override
        public List<AllocationDisplay> findAllocationDisplaysBySettlementIdAndStoreId(
            UUID posSettlementId,
            StoreId storeId
        ) {
            return List.of();
        }

        @Override
        public void insert(PosSettlement settlement) {
            insertedSettlements.add(settlement);
            settlementsByIdempotencyKey.put(settlement.idempotencyKey(), settlement);
        }
    }
}
