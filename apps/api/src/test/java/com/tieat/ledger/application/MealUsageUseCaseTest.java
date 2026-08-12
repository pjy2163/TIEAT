package com.tieat.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tieat.ledger.domain.EntrySource;
import com.tieat.ledger.domain.MealUsage;
import com.tieat.ledger.domain.MealUsageId;
import com.tieat.ledger.domain.MealUsageRepository;
import com.tieat.ledger.domain.MealUsageSlice;
import com.tieat.ledger.domain.MealUsageStatus;
import com.tieat.ledger.domain.PublicMealUsageIdempotency;
import com.tieat.ledger.domain.PublicMealUsageIdempotencyRepository;
import com.tieat.partnership.domain.MealContract;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.partnership.domain.MealContractPaymentType;
import com.tieat.partnership.domain.MealContractRepository;
import com.tieat.partnership.domain.QrSelectableMealContract;
import com.tieat.partnership.domain.PartnerOrganizationId;
import com.tieat.qr.domain.MealUsageQrContext;
import com.tieat.qr.domain.MealUsageQrContextId;
import com.tieat.qr.domain.MealUsageQrContextRepository;
import com.tieat.qr.domain.MealUsageQrToken;
import com.tieat.store.domain.StoreId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MealUsageUseCaseTest {

    private static final Instant SERVER_TIME = Instant.parse("2026-08-05T09:15:30Z");

    @Test
    void createsPendingUsageAndSavesIt() {
        InMemoryMealUsageRepository repository = new InMemoryMealUsageRepository();
        InMemoryMealContractRepository mealContractRepository = new InMemoryMealContractRepository();
        mealContractRepository.save(prepaidContract(12_000));
        CreateMealUsageUseCase useCase = new CreateMealUsageUseCase(
            repository,
            mealContractRepository,
            Clock.fixed(SERVER_TIME, ZoneOffset.UTC)
        );

        MealUsage created = useCase.create(new CreateMealUsageCommand(
            storeId(), mealContractId(), EntrySource.PARTNER_MOBILE, 12_000
        ));

        assertThat(created.status()).isEqualTo(MealUsageStatus.PENDING);
        assertThat(created.storeId()).isEqualTo(storeId());
        assertThat(created.mealContractId()).isEqualTo(mealContractId());
        assertThat(created.entrySource()).isEqualTo(EntrySource.PARTNER_MOBILE);
        assertThat(created.createdAt()).isEqualTo(SERVER_TIME);
        assertThat(repository.findById(created.id())).containsSame(created);
        assertThat(repository.saveCount).isEqualTo(1);
        assertThat(mealContractRepository.nonlockingId).isEqualTo(mealContractId());
        assertThat(mealContractRepository.lockedId).isNull();
        assertThat(mealContractRepository.findById(mealContractId()).orElseThrow().prepaidBalance()).isEqualTo(12_000);
    }

    @Test
    void rejectsMissingOrCrossStoreContractWithoutSavingUsage() {
        InMemoryMealUsageRepository repository = new InMemoryMealUsageRepository();
        InMemoryMealContractRepository mealContractRepository = new InMemoryMealContractRepository();
        CreateMealUsageUseCase useCase = new CreateMealUsageUseCase(
            repository,
            mealContractRepository,
            Clock.fixed(SERVER_TIME, ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> useCase.create(new CreateMealUsageCommand(
            storeId(), mealContractId(), EntrySource.STORE_TABLET, 12_000
        )))
            .isInstanceOf(MealContractNotFoundException.class);
        assertThat(repository.saveCount).isZero();

        mealContractRepository.save(new MealContract(
            mealContractId(),
            new StoreId(UUID.fromString("6142be7d-0dc9-4f77-a17d-07e1e5c6e9a1")),
            MealContractPaymentType.PREPAID_WITH_RECEIVABLE_OVERFLOW,
            12_000
        ));

        assertThatThrownBy(() -> useCase.create(new CreateMealUsageCommand(
            storeId(), mealContractId(), EntrySource.STORE_TABLET, 12_000
        )))
            .isInstanceOf(MealContractNotFoundException.class);
        assertThat(repository.saveCount).isZero();
        assertThat(mealContractRepository.lockedId).isNull();
    }

    @Test
    void locksContractThenConfirmsAndSavesUsageWithServerClock() {
        InMemoryMealUsageRepository repository = new InMemoryMealUsageRepository();
        InMemoryMealContractRepository mealContractRepository = new InMemoryMealContractRepository();
        MealUsage pending = pendingUsage();
        repository.save(pending);
        mealContractRepository.save(prepaidContract(5_000));
        ConfirmMealUsageUseCase useCase = new ConfirmMealUsageUseCase(
            repository,
            mealContractRepository,
            Clock.fixed(SERVER_TIME, ZoneOffset.UTC)
        );

        MealUsage confirmed = useCase.confirm(new ConfirmMealUsageCommand(pending.id(), storeId(), "HK"));

        assertThat(confirmed.status()).isEqualTo(MealUsageStatus.CONFIRMED);
        assertThat(confirmed.confirmation()).hasValueSatisfying(confirmation -> {
            assertThat(confirmation.staffInitials()).isEqualTo("HK");
            assertThat(confirmation.confirmedAt()).isEqualTo(SERVER_TIME);
        });
        assertThat(confirmed.prepaidAllocation()).hasValueSatisfying(allocation -> {
            assertThat(allocation.prepaidApplied()).isEqualTo(5_000);
            assertThat(allocation.receivableCreated()).isEqualTo(7_000);
        });
        assertThat(repository.saveCount).isEqualTo(2);
        assertThat(mealContractRepository.lockedId).isEqualTo(mealContractId());
        assertThat(mealContractRepository.findByIdForUpdate(mealContractId()).orElseThrow().prepaidBalance())
            .isZero();
    }

    @Test
    void rejectsConfirmationWhenUsageDoesNotExist() {
        ConfirmMealUsageUseCase useCase = new ConfirmMealUsageUseCase(
            new InMemoryMealUsageRepository(),
            new InMemoryMealContractRepository(),
            Clock.fixed(SERVER_TIME, ZoneOffset.UTC)
        );
        MealUsageId missingId = new MealUsageId(UUID.fromString("cb36c29b-dc69-4bd5-9f2a-5892734a3049"));

        assertThatThrownBy(() -> useCase.confirm(new ConfirmMealUsageCommand(missingId, storeId(), "HK")))
            .isInstanceOf(MealUsageNotFoundException.class)
            .hasMessageContaining(missingId.value().toString());
    }

    @Test
    void rejectsInvalidConfirmationInputBeforeLoadingUsage() {
        assertThatIllegalArgumentException().isThrownBy(
            () -> new ConfirmMealUsageCommand(pendingUsage().id(), storeId(), " ")
        );
        assertThatIllegalArgumentException().isThrownBy(
            () -> new CreateMealUsageCommand(storeId(), mealContractId(), EntrySource.PARTNER_MOBILE, 0)
        );
    }

    @Test
    void rejectsRepeatedConfirmationWithoutSavingAgain() {
        InMemoryMealUsageRepository repository = new InMemoryMealUsageRepository();
        InMemoryMealContractRepository mealContractRepository = new InMemoryMealContractRepository();
        MealUsage alreadyConfirmed = pendingUsage();
        alreadyConfirmed.confirm("HK", SERVER_TIME, new com.tieat.ledger.domain.PrepaidAllocation(12_000, 12_000, 0, 0));
        repository.save(alreadyConfirmed);
        mealContractRepository.save(prepaidContract(12_000));
        ConfirmMealUsageUseCase useCase = new ConfirmMealUsageUseCase(
            repository,
            mealContractRepository,
            Clock.fixed(SERVER_TIME.plusSeconds(1), ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> useCase.confirm(new ConfirmMealUsageCommand(alreadyConfirmed.id(), storeId(), "HK")))
            .isInstanceOf(MealUsageAlreadyConfirmedException.class);
        assertThat(repository.saveCount).isEqualTo(1);
        assertThat(mealContractRepository.saveCount).isEqualTo(1);
    }

    @Test
    void rejectsMissingContractWithoutConfirmingUsage() {
        InMemoryMealUsageRepository repository = new InMemoryMealUsageRepository();
        MealUsage pending = pendingUsage();
        repository.save(pending);
        ConfirmMealUsageUseCase useCase = new ConfirmMealUsageUseCase(
            repository,
            new InMemoryMealContractRepository(),
            Clock.fixed(SERVER_TIME, ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> useCase.confirm(new ConfirmMealUsageCommand(pending.id(), storeId(), "HK")))
            .isInstanceOf(MealContractNotFoundException.class);
        assertThat(pending.status()).isEqualTo(MealUsageStatus.PENDING);
        assertThat(repository.saveCount).isEqualTo(1);
    }

    @Test
    void rejectsMismatchedContractStoreWithoutConfirmingUsage() {
        InMemoryMealUsageRepository repository = new InMemoryMealUsageRepository();
        InMemoryMealContractRepository mealContractRepository = new InMemoryMealContractRepository();
        MealUsage pending = pendingUsage();
        repository.save(pending);
        mealContractRepository.save(new MealContract(
            mealContractId(),
            new StoreId(UUID.fromString("6142be7d-0dc9-4f77-a17d-07e1e5c6e9a1")),
            MealContractPaymentType.PREPAID_WITH_RECEIVABLE_OVERFLOW,
            12_000
        ));
        ConfirmMealUsageUseCase useCase = new ConfirmMealUsageUseCase(
            repository,
            mealContractRepository,
            Clock.fixed(SERVER_TIME, ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> useCase.confirm(new ConfirmMealUsageCommand(pending.id(), storeId(), "HK")))
            .isInstanceOf(MealUsageContractScopeMismatchException.class);
        assertThat(pending.status()).isEqualTo(MealUsageStatus.PENDING);
        assertThat(mealContractRepository.findByIdForUpdate(mealContractId()).orElseThrow().prepaidBalance())
            .isEqualTo(12_000);
    }

    @Test
    void createsPublicQrPendingUsageOnceWithServerScopeAndPartnerSnapshot() {
        InMemoryMealUsageRepository usageRepository = new InMemoryMealUsageRepository();
        InMemoryMealContractRepository contractRepository = new InMemoryMealContractRepository();
        InMemoryMealUsageQrContextRepository qrContextRepository = new InMemoryMealUsageQrContextRepository();
        InMemoryPublicMealUsageIdempotencyRepository idempotencyRepository = new InMemoryPublicMealUsageIdempotencyRepository();
        PartnerOrganizationId partnerOrganizationId = new PartnerOrganizationId(UUID.fromString("bd4fdd71-5263-44c7-bcfd-48a19cbd647d"));
        contractRepository.save(new MealContract(
            mealContractId(),
            storeId(),
            MealContractPaymentType.PREPAID_WITH_RECEIVABLE_OVERFLOW,
            12_000,
            partnerOrganizationId,
            true
        ));
        String token = MealUsageQrToken.generate();
        MealUsageQrContextId qrContextId = new MealUsageQrContextId(UUID.fromString("e7f94de9-97d2-47ad-8db0-23d0bdd799d4"));
        qrContextRepository.add(new MealUsageQrContext(
            qrContextId,
            storeId(),
            "강남점",
            MealUsageQrToken.sha256Hash(token),
            SERVER_TIME,
            SERVER_TIME.plusSeconds(60),
            null
        ));
        CreatePublicMealUsageUseCase useCase = new CreatePublicMealUsageUseCase(
            qrContextRepository,
            contractRepository,
            usageRepository,
            idempotencyRepository,
            Clock.fixed(SERVER_TIME, ZoneOffset.UTC)
        );
        UUID idempotencyKey = UUID.fromString("3279f750-a0d5-4978-81d5-a5da1a8d7b5a");
        CreatePublicMealUsageCommand command = new CreatePublicMealUsageCommand(
            token, idempotencyKey, mealContractId(), 12_000
        );

        MealUsage created = useCase.create(command);
        MealUsage replayed = useCase.create(command);

        assertThat(created.id()).isEqualTo(replayed.id());
        assertThat(created.status()).isEqualTo(MealUsageStatus.PENDING);
        assertThat(created.storeId()).isEqualTo(storeId());
        assertThat(created.entrySource()).isEqualTo(EntrySource.PARTNER_MOBILE);
        assertThat(created.createdAt()).isEqualTo(SERVER_TIME);
        assertThat(created.publicQrContextId()).contains(qrContextId);
        assertThat(created.partnerDisplayNameSnapshot()).isPresent();
        assertThat(usageRepository.saveCount).isEqualTo(1);
        assertThat(idempotencyRepository.saveCount).isEqualTo(1);
        assertThat(contractRepository.findById(mealContractId()).orElseThrow().prepaidBalance()).isEqualTo(12_000);

        assertThatThrownBy(() -> useCase.create(new CreatePublicMealUsageCommand(
            token, idempotencyKey, mealContractId(), 12_001
        ))).isInstanceOf(PublicMealUsageIdempotencyConflictException.class);
    }

    @Test
    void rejectsPendingUsageWithoutChangingItsContractBalance() {
        InMemoryMealUsageRepository usageRepository = new InMemoryMealUsageRepository();
        InMemoryMealContractRepository contractRepository = new InMemoryMealContractRepository();
        MealUsage pending = pendingUsage();
        usageRepository.save(pending);
        contractRepository.save(prepaidContract(12_000));
        RejectMealUsageUseCase useCase = new RejectMealUsageUseCase(
            usageRepository,
            Clock.fixed(SERVER_TIME, ZoneOffset.UTC)
        );

        MealUsage rejected = useCase.reject(new RejectMealUsageCommand(pending.id(), storeId(), "store-hk"));

        assertThat(rejected.status()).isEqualTo(MealUsageStatus.REJECTED);
        assertThat(rejected.rejection()).contains(new com.tieat.ledger.domain.Rejection("store-hk", SERVER_TIME));
        assertThat(rejected.confirmation()).isEmpty();
        assertThat(rejected.prepaidAllocation()).isEmpty();
        assertThat(contractRepository.findById(mealContractId()).orElseThrow().prepaidBalance()).isEqualTo(12_000);
        assertThatThrownBy(() -> useCase.reject(new RejectMealUsageCommand(pending.id(), storeId(), "store-hk")))
            .isInstanceOf(MealUsageNotPendingException.class);
    }

    private MealUsage pendingUsage() {
        return MealUsage.pending(
            new MealUsageId(UUID.fromString("d9ef1fd5-2a3c-4fce-bb20-0d1b26a634ab")),
            storeId(),
            mealContractId(),
            EntrySource.PARTNER_MOBILE,
            12_000,
            SERVER_TIME.minusSeconds(60)
        );
    }

    private StoreId storeId() {
        return new StoreId(UUID.fromString("9d5e37dd-dbe2-40dc-97fb-8e77c89aa4cb"));
    }

    private MealContractId mealContractId() {
        return new MealContractId(UUID.fromString("019c0f9c-6d58-7d37-b0e3-1af21f7124b9"));
    }

    private MealContract prepaidContract(long prepaidBalance) {
        return new MealContract(
            mealContractId(), storeId(), MealContractPaymentType.PREPAID_WITH_RECEIVABLE_OVERFLOW, prepaidBalance
        );
    }

    private static final class InMemoryMealUsageRepository implements MealUsageRepository {

        private final Map<MealUsageId, MealUsage> mealUsages = new HashMap<>();
        private int saveCount;

        @Override
        public MealUsage save(MealUsage mealUsage) {
            mealUsages.put(mealUsage.id(), mealUsage);
            saveCount++;
            return mealUsage;
        }

        @Override
        public Optional<MealUsage> findById(MealUsageId id) {
            return Optional.ofNullable(mealUsages.get(id));
        }

        @Override
        public MealUsageSlice findPendingByStoreId(StoreId storeId, int page, int size) {
            List<MealUsage> pending = mealUsages.values().stream()
                .filter(usage -> usage.storeId().equals(storeId))
                .filter(usage -> usage.status() == MealUsageStatus.PENDING)
                .sorted(Comparator.comparing(MealUsage::createdAt).thenComparing(usage -> usage.id().value().toString()))
                .toList();
            int fromIndex = Math.min(page * size, pending.size());
            int toIndex = Math.min(fromIndex + size, pending.size());
            return new MealUsageSlice(pending.subList(fromIndex, toIndex), toIndex < pending.size());
        }

        @Override
        public MealUsageSlice findConfirmedByStoreIdAndCreatedAtBetween(
            StoreId storeId,
            Instant startInclusive,
            Instant endExclusive,
            int page,
            int size
        ) {
            List<MealUsage> monthly = mealUsages.values().stream()
                .filter(usage -> usage.storeId().equals(storeId))
                .filter(usage -> usage.status() == MealUsageStatus.CONFIRMED)
                .filter(usage -> !usage.createdAt().isBefore(startInclusive))
                .filter(usage -> usage.createdAt().isBefore(endExclusive))
                .sorted(Comparator.<MealUsage, Instant>comparing(MealUsage::createdAt).reversed()
                    .thenComparing(usage -> usage.id().value(), Comparator.reverseOrder()))
                .toList();
            int fromIndex = Math.min(page * size, monthly.size());
            int toIndex = Math.min(fromIndex + size, monthly.size());
            return new MealUsageSlice(monthly.subList(fromIndex, toIndex), toIndex < monthly.size());
        }

        @Override
        public long countPublicQrCreatedSince(
            com.tieat.qr.domain.MealUsageQrContextId qrContextId,
            java.time.Instant since
        ) {
            return mealUsages.values().stream()
                .filter(usage -> usage.publicQrContextId().filter(qrContextId::equals).isPresent())
                .filter(usage -> !usage.createdAt().isBefore(since))
                .count();
        }
    }

    private static final class InMemoryMealContractRepository implements MealContractRepository {

        private final Map<MealContractId, MealContract> mealContracts = new HashMap<>();
        private MealContractId nonlockingId;
        private MealContractId lockedId;
        private int saveCount;

        @Override
        public Optional<MealContract> findById(MealContractId id) {
            nonlockingId = id;
            return Optional.ofNullable(mealContracts.get(id));
        }

        @Override
        public Optional<MealContract> findByIdForUpdate(MealContractId id) {
            lockedId = id;
            return Optional.ofNullable(mealContracts.get(id));
        }

        @Override
        public List<QrSelectableMealContract> findQrSelectableByStoreId(StoreId storeId) {
            return mealContracts.values().stream()
                .filter(contract -> contract.storeId().equals(storeId))
                .filter(MealContract::isQrSelectable)
                .flatMap(contract -> contract.partnerOrganizationId().stream().map(partnerId ->
                    new QrSelectableMealContract(contract.id(), partnerId.value().toString())
                ))
                .toList();
        }

        @Override
        public MealContract save(MealContract mealContract) {
            mealContracts.put(mealContract.id(), mealContract);
            saveCount++;
            return mealContract;
        }
    }

    private static final class InMemoryMealUsageQrContextRepository implements MealUsageQrContextRepository {

        private final Map<String, MealUsageQrContext> contextsByTokenHash = new HashMap<>();

        void add(MealUsageQrContext context) {
            contextsByTokenHash.put(context.tokenHash(), context);
        }

        @Override
        public Optional<MealUsageQrContext> findByTokenHash(String tokenHash) {
            return Optional.ofNullable(contextsByTokenHash.get(tokenHash));
        }

        @Override
        public Optional<MealUsageQrContext> findByTokenHashForUpdate(String tokenHash) {
            return findByTokenHash(tokenHash);
        }
    }

    private static final class InMemoryPublicMealUsageIdempotencyRepository implements PublicMealUsageIdempotencyRepository {

        private final Map<String, PublicMealUsageIdempotency> idempotencyRecords = new HashMap<>();
        private int saveCount;

        @Override
        public Optional<PublicMealUsageIdempotency> findByQrContextIdAndKey(
            MealUsageQrContextId qrContextId,
            UUID idempotencyKey
        ) {
            return Optional.ofNullable(idempotencyRecords.get(qrContextId.value() + ":" + idempotencyKey));
        }

        @Override
        public PublicMealUsageIdempotency save(PublicMealUsageIdempotency idempotency) {
            idempotencyRecords.put(idempotency.qrContextId().value() + ":" + idempotency.idempotencyKey(), idempotency);
            saveCount++;
            return idempotency;
        }
    }
}
