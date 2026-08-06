package com.tieat.ledger.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tieat.ledger.application.ConfirmMealUsageCommand;
import com.tieat.ledger.application.ConfirmMealUsageUseCase;
import com.tieat.ledger.application.MealContractNotFoundException;
import com.tieat.ledger.application.MealUsageContractScopeMismatchException;
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
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
class ContractBalanceConfirmationIntegrationTest {

    private static final StoreId STORE_ID = new StoreId(
        UUID.fromString("9d5e37dd-dbe2-40dc-97fb-8e77c89aa4cb")
    );

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
        DockerImageName.parse("postgres:18-alpine")
    )
        .withDatabaseName("tieat")
        .withUsername("tieat")
        .withPassword("tieat");

    @Autowired
    private ConfirmMealUsageUseCase confirmMealUsageUseCase;

    @Autowired
    private MealUsageRepository mealUsageRepository;

    @Autowired
    private MealContractRepository mealContractRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void mapsContractAndEnforcesDatabaseBalanceConstraints() {
        MealContract contract = prepaidContract(10_000);

        mealContractRepository.save(contract);

        MealContract reloaded = transactionTemplate.execute(status -> mealContractRepository
            .findByIdForUpdate(contract.id())
            .orElseThrow());
        assertThat(reloaded.id()).isEqualTo(contract.id());
        assertThat(reloaded.storeId()).isEqualTo(STORE_ID);
        assertThat(reloaded.paymentType()).isEqualTo(MealContractPaymentType.PREPAID_WITH_RECEIVABLE_OVERFLOW);
        assertThat(reloaded.prepaidBalance()).isEqualTo(10_000);

        assertThatThrownBy(() -> jdbcTemplate.update(
            "insert into meal_contracts (id, store_id, payment_type, prepaid_balance) values (?, ?, ?, ?)",
            UUID.randomUUID(), STORE_ID.value(), "POSTPAID", 1
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
            "insert into meal_contracts (id, store_id, payment_type, prepaid_balance) values (?, ?, ?, ?)",
            UUID.randomUUID(), STORE_ID.value(), "PREPAID_WITH_RECEIVABLE_OVERFLOW", -1
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void confirmsPrepaidUsageAndCreatesReceivableForOverflow() {
        MealContract contract = prepaidContract(10_000);
        MealUsage usage = pendingUsage(contract.id(), STORE_ID, 12_000);
        mealContractRepository.save(contract);
        mealUsageRepository.save(usage);

        MealUsage confirmed = confirmMealUsageUseCase.confirm(new ConfirmMealUsageCommand(usage.id(), "HK"));

        assertThat(confirmed.status()).isEqualTo(MealUsageStatus.CONFIRMED);
        assertThat(confirmed.prepaidAllocation()).hasValueSatisfying(allocation -> {
            assertThat(allocation.prepaidApplied()).isEqualTo(10_000);
            assertThat(allocation.receivableCreated()).isEqualTo(2_000);
            assertThat(allocation.remainingPrepaid()).isZero();
        });
        assertThat(reloadUsage(usage.id()).status()).isEqualTo(MealUsageStatus.CONFIRMED);
        assertThat(reloadContract(contract.id()).prepaidBalance()).isZero();
    }

    @Test
    void confirmsPostpaidUsageAsEntireReceivable() {
        MealContract contract = postpaidContract();
        MealUsage usage = pendingUsage(contract.id(), STORE_ID, 8_000);
        mealContractRepository.save(contract);
        mealUsageRepository.save(usage);

        MealUsage confirmed = confirmMealUsageUseCase.confirm(new ConfirmMealUsageCommand(usage.id(), "HK"));

        assertThat(confirmed.prepaidAllocation()).hasValueSatisfying(allocation -> {
            assertThat(allocation.prepaidApplied()).isZero();
            assertThat(allocation.receivableCreated()).isEqualTo(8_000);
            assertThat(allocation.remainingPrepaid()).isZero();
        });
        assertThat(reloadContract(contract.id()).prepaidBalance()).isZero();
    }

    @Test
    void rollsBackWhenContractIsMissingOrOutsideUsageStoreScope() {
        MealContractId missingContractId = contractId();
        MealUsage missingContractUsage = pendingUsage(missingContractId, STORE_ID, 8_000);
        mealUsageRepository.save(missingContractUsage);

        assertThatThrownBy(() -> confirmMealUsageUseCase.confirm(
            new ConfirmMealUsageCommand(missingContractUsage.id(), "HK")
        )).isInstanceOf(MealContractNotFoundException.class);
        assertThat(reloadUsage(missingContractUsage.id()).status()).isEqualTo(MealUsageStatus.PENDING);

        MealContract mismatchedContract = prepaidContract(10_000);
        MealUsage mismatchedUsage = pendingUsage(
            mismatchedContract.id(),
            new StoreId(UUID.fromString("6142be7d-0dc9-4f77-a17d-07e1e5c6e9a1")),
            8_000
        );
        mealContractRepository.save(mismatchedContract);
        mealUsageRepository.save(mismatchedUsage);

        assertThatThrownBy(() -> confirmMealUsageUseCase.confirm(
            new ConfirmMealUsageCommand(mismatchedUsage.id(), "HK")
        )).isInstanceOf(MealUsageContractScopeMismatchException.class);
        assertThat(reloadUsage(mismatchedUsage.id()).status()).isEqualTo(MealUsageStatus.PENDING);
        assertThat(reloadContract(mismatchedContract.id()).prepaidBalance()).isEqualTo(10_000);
    }

    @Test
    void rejectsRepeatedConfirmationWithoutAnotherDebit() {
        MealContract contract = prepaidContract(10_000);
        MealUsage usage = pendingUsage(contract.id(), STORE_ID, 8_000);
        mealContractRepository.save(contract);
        mealUsageRepository.save(usage);

        confirmMealUsageUseCase.confirm(new ConfirmMealUsageCommand(usage.id(), "HK"));

        assertThatThrownBy(() -> confirmMealUsageUseCase.confirm(new ConfirmMealUsageCommand(usage.id(), "JS")))
            .isInstanceOf(IllegalStateException.class);
        assertThat(reloadContract(contract.id()).prepaidBalance()).isEqualTo(2_000);
        assertThat(reloadUsage(usage.id()).confirmation()).hasValueSatisfying(
            confirmation -> assertThat(confirmation.staffInitials()).isEqualTo("HK")
        );
    }

    @Test
    void serializesTwoDifferentUsagesAgainstTheSameContractBalance() throws Exception {
        MealContract contract = prepaidContract(10_000);
        MealUsage firstUsage = pendingUsage(contract.id(), STORE_ID, 8_000);
        MealUsage secondUsage = pendingUsage(contract.id(), STORE_ID, 8_000);
        mealContractRepository.save(contract);
        mealUsageRepository.save(firstUsage);
        mealUsageRepository.save(secondUsage);

        CountDownLatch firstLockAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MealUsage> first = executor.submit(() -> transactionTemplate.execute(status -> {
                mealContractRepository.findByIdForUpdate(contract.id()).orElseThrow();
                firstLockAcquired.countDown();
                await(releaseFirst);
                return confirmMealUsageUseCase.confirm(new ConfirmMealUsageCommand(firstUsage.id(), "HK"));
            }));
            assertThat(firstLockAcquired.await(5, TimeUnit.SECONDS)).isTrue();

            Future<MealUsage> second = executor.submit(() -> {
                secondStarted.countDown();
                return confirmMealUsageUseCase.confirm(new ConfirmMealUsageCommand(secondUsage.id(), "JS"));
            });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(second.isDone()).isFalse();

            releaseFirst.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).prepaidAllocation()).contains(
                new com.tieat.ledger.domain.PrepaidAllocation(8_000, 8_000, 0, 2_000)
            );
            assertThat(second.get(5, TimeUnit.SECONDS).prepaidAllocation()).contains(
                new com.tieat.ledger.domain.PrepaidAllocation(8_000, 2_000, 6_000, 0)
            );
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }

        MealUsage reloadedFirst = reloadUsage(firstUsage.id());
        MealUsage reloadedSecond = reloadUsage(secondUsage.id());
        assertThat(reloadedFirst.status()).isEqualTo(MealUsageStatus.CONFIRMED);
        assertThat(reloadedSecond.status()).isEqualTo(MealUsageStatus.CONFIRMED);
        long prepaidTotal = reloadedFirst.prepaidAllocation().orElseThrow().prepaidApplied()
            + reloadedSecond.prepaidAllocation().orElseThrow().prepaidApplied();
        long receivableTotal = reloadedFirst.prepaidAllocation().orElseThrow().receivableCreated()
            + reloadedSecond.prepaidAllocation().orElseThrow().receivableCreated();
        assertThat(prepaidTotal).isEqualTo(10_000);
        assertThat(receivableTotal).isEqualTo(6_000);
        assertThat(reloadContract(contract.id()).prepaidBalance()).isZero();
    }

    @Test
    void rollsBackContractDebitWhenAStaleUsageLosesTheRace() throws Exception {
        MealContract contract = prepaidContract(10_000);
        MealUsage usage = pendingUsage(contract.id(), STORE_ID, 8_000);
        mealContractRepository.save(contract);
        mealUsageRepository.save(usage);

        CountDownLatch firstLockAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MealUsage> first = executor.submit(() -> transactionTemplate.execute(status -> {
                mealContractRepository.findByIdForUpdate(contract.id()).orElseThrow();
                firstLockAcquired.countDown();
                await(releaseFirst);
                return confirmMealUsageUseCase.confirm(new ConfirmMealUsageCommand(usage.id(), "HK"));
            }));
            assertThat(firstLockAcquired.await(5, TimeUnit.SECONDS)).isTrue();

            Future<MealUsage> stale = executor.submit(() -> {
                secondStarted.countDown();
                return confirmMealUsageUseCase.confirm(new ConfirmMealUsageCommand(usage.id(), "JS"));
            });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(stale.isDone()).isFalse();

            releaseFirst.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo(MealUsageStatus.CONFIRMED);
            assertThatThrownBy(() -> stale.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ObjectOptimisticLockingFailureException.class);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }

        assertThat(reloadContract(contract.id()).prepaidBalance()).isEqualTo(2_000);
        assertThat(reloadUsage(usage.id()).status()).isEqualTo(MealUsageStatus.CONFIRMED);
        assertThat(reloadUsage(usage.id()).confirmation()).hasValueSatisfying(
            confirmation -> assertThat(confirmation.staffInitials()).isEqualTo("HK")
        );
    }

    private MealUsage reloadUsage(MealUsageId id) {
        return transactionTemplate.execute(status -> mealUsageRepository.findById(id).orElseThrow());
    }

    private MealContract reloadContract(MealContractId id) {
        return transactionTemplate.execute(status -> mealContractRepository.findByIdForUpdate(id).orElseThrow());
    }

    private MealContract prepaidContract(long prepaidBalance) {
        return new MealContract(
            contractId(), STORE_ID, MealContractPaymentType.PREPAID_WITH_RECEIVABLE_OVERFLOW, prepaidBalance
        );
    }

    private MealContract postpaidContract() {
        return new MealContract(contractId(), STORE_ID, MealContractPaymentType.POSTPAID, 0);
    }

    private MealUsage pendingUsage(MealContractId contractId, StoreId storeId, long amount) {
        return MealUsage.pending(
            new MealUsageId(UUID.randomUUID()),
            storeId,
            contractId,
            EntrySource.STORE_TABLET,
            amount,
            Instant.parse("2026-08-05T09:14:30Z")
        );
    }

    private MealContractId contractId() {
        return new MealContractId(UUID.randomUUID());
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test transaction coordination");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating test transaction", exception);
        }
    }
}
