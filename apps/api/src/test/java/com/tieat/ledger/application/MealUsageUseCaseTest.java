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
import com.tieat.partnership.domain.MealContractId;
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
    void loadsConfirmsAndSavesUsageWithServerClockAndAuthoritativePrepaid() {
        InMemoryMealUsageRepository repository = new InMemoryMealUsageRepository();
        MealUsage pending = pendingUsage();
        repository.save(pending);
        ConfirmMealUsageUseCase useCase = new ConfirmMealUsageUseCase(
            repository,
            Clock.fixed(SERVER_TIME, ZoneOffset.UTC)
        );

        MealUsage confirmed = useCase.confirm(new ConfirmMealUsageCommand(pending.id(), "HK", 5_000));

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
    }

    @Test
    void rejectsConfirmationWhenUsageDoesNotExist() {
        ConfirmMealUsageUseCase useCase = new ConfirmMealUsageUseCase(
            new InMemoryMealUsageRepository(),
            Clock.fixed(SERVER_TIME, ZoneOffset.UTC)
        );
        MealUsageId missingId = new MealUsageId(UUID.fromString("cb36c29b-dc69-4bd5-9f2a-5892734a3049"));

        assertThatThrownBy(() -> useCase.confirm(new ConfirmMealUsageCommand(missingId, "HK", 0)))
            .isInstanceOf(MealUsageNotFoundException.class)
            .hasMessageContaining(missingId.value().toString());
    }

    @Test
    void rejectsInvalidConfirmationInputBeforeLoadingUsage() {
        assertThatIllegalArgumentException().isThrownBy(
            () -> new ConfirmMealUsageCommand(pendingUsage().id(), " ", 0)
        );
        assertThatIllegalArgumentException().isThrownBy(
            () -> new ConfirmMealUsageCommand(pendingUsage().id(), "HK", -1)
        );
        assertThatIllegalArgumentException().isThrownBy(
            () -> new CreateMealUsageCommand(storeId(), mealContractId(), EntrySource.PARTNER_MOBILE, 0)
        );
    }

    @Test
    void rejectsRepeatedConfirmationWithoutSavingAgain() {
        InMemoryMealUsageRepository repository = new InMemoryMealUsageRepository();
        MealUsage alreadyConfirmed = pendingUsage();
        alreadyConfirmed.confirm("HK", SERVER_TIME, 12_000);
        repository.save(alreadyConfirmed);
        ConfirmMealUsageUseCase useCase = new ConfirmMealUsageUseCase(
            repository,
            Clock.fixed(SERVER_TIME.plusSeconds(1), ZoneOffset.UTC)
        );

        assertThatIllegalStateException().isThrownBy(
            () -> useCase.confirm(new ConfirmMealUsageCommand(alreadyConfirmed.id(), "HK", 12_000))
        );
        assertThat(repository.saveCount).isEqualTo(1);
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
}
