package com.tieat.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tieat.ledger.domain.EntrySource;
import com.tieat.ledger.domain.MealUsage;
import com.tieat.ledger.domain.MealUsageId;
import com.tieat.ledger.domain.MealUsageRepository;
import com.tieat.ledger.domain.MealUsageStatus;
import com.tieat.partnership.domain.MealContract;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.partnership.domain.MealContractPaymentType;
import com.tieat.partnership.domain.MealContractRepository;
import com.tieat.store.domain.StoreId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MealUsageUseCaseTest {

    private static final Instant SERVER_TIME = Instant.parse("2026-08-05T09:15:30Z");

    @Test
    void createsPendingUsageAndSavesIt() {
        InMemoryMealUsageRepository repository = new InMemoryMealUsageRepository();
        CreateMealUsageUseCase useCase = new CreateMealUsageUseCase(
            repository,
            Clock.fixed(SERVER_TIME, ZoneOffset.UTC)
        );

        MealUsage created = useCase.create(new CreateMealUsageCommand(
            storeId(), mealContractId(), EntrySource.PARTNER_MOBILE, 12_000
        ));

        assertThat(created.status()).isEqualTo(MealUsageStatus.PENDING);
        assertThat(created.storeId()).isEqualTo(storeId());
        assertThat(created.mealContractId()).isEqualTo(mealContractId());
        assertThat(created.createdAt()).isEqualTo(SERVER_TIME);
        assertThat(repository.findById(created.id())).containsSame(created);
        assertThat(repository.saveCount).isEqualTo(1);
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

        MealUsage confirmed = useCase.confirm(new ConfirmMealUsageCommand(pending.id(), "HK"));

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

        assertThatThrownBy(() -> useCase.confirm(new ConfirmMealUsageCommand(missingId, "HK")))
            .isInstanceOf(MealUsageNotFoundException.class)
            .hasMessageContaining(missingId.value().toString());
    }

    @Test
    void rejectsInvalidConfirmationInputBeforeLoadingUsage() {
        assertThatIllegalArgumentException().isThrownBy(
            () -> new ConfirmMealUsageCommand(pendingUsage().id(), " ")
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

        assertThatIllegalStateException().isThrownBy(
            () -> useCase.confirm(new ConfirmMealUsageCommand(alreadyConfirmed.id(), "HK"))
        );
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

        assertThatThrownBy(() -> useCase.confirm(new ConfirmMealUsageCommand(pending.id(), "HK")))
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

        assertThatThrownBy(() -> useCase.confirm(new ConfirmMealUsageCommand(pending.id(), "HK")))
            .isInstanceOf(MealUsageContractScopeMismatchException.class);
        assertThat(pending.status()).isEqualTo(MealUsageStatus.PENDING);
        assertThat(mealContractRepository.findByIdForUpdate(mealContractId()).orElseThrow().prepaidBalance())
            .isEqualTo(12_000);
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
    }

    private static final class InMemoryMealContractRepository implements MealContractRepository {

        private final Map<MealContractId, MealContract> mealContracts = new HashMap<>();
        private MealContractId lockedId;
        private int saveCount;

        @Override
        public Optional<MealContract> findByIdForUpdate(MealContractId id) {
            lockedId = id;
            return Optional.ofNullable(mealContracts.get(id));
        }

        @Override
        public MealContract save(MealContract mealContract) {
            mealContracts.put(mealContract.id(), mealContract);
            saveCount++;
            return mealContract;
        }
    }
}
