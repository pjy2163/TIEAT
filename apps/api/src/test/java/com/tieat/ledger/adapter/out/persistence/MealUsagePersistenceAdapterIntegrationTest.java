package com.tieat.ledger.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tieat.ledger.domain.EntrySource;
import com.tieat.ledger.domain.Confirmation;
import com.tieat.ledger.domain.MealUsage;
import com.tieat.ledger.domain.MealUsageId;
import com.tieat.ledger.domain.MealUsageRepository;
import com.tieat.ledger.domain.MealUsageStatus;
import com.tieat.ledger.domain.PrepaidAllocation;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.store.domain.StoreId;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
class MealUsagePersistenceAdapterIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
        DockerImageName.parse("postgres:18-alpine")
    )
        .withDatabaseName("tieat")
        .withUsername("tieat")
        .withPassword("tieat");

    @Autowired
    private MealUsageRepository mealUsageRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    @Transactional
    void savesAndReloadsPendingMealUsageFromPostgreSql() {
        MealUsageId id = new MealUsageId(UUID.fromString("d9ef1fd5-2a3c-4fce-bb20-0d1b26a634ab"));
        StoreId storeId = new StoreId(UUID.fromString("9d5e37dd-dbe2-40dc-97fb-8e77c89aa4cb"));
        MealContractId mealContractId = new MealContractId(
            UUID.fromString("019c0f9c-6d58-7d37-b0e3-1af21f7124b9")
        );
        Instant createdAt = Instant.parse("2026-08-05T09:14:30Z");
        MealUsage pending = MealUsage.pending(
            id, storeId, mealContractId, EntrySource.PARTNER_MOBILE, 12_000, createdAt
        );

        mealUsageRepository.save(pending);
        entityManager.flush();
        entityManager.clear();

        MealUsage reloaded = mealUsageRepository.findById(id).orElseThrow();

        assertThat(reloaded.id()).isEqualTo(id);
        assertThat(reloaded.storeId()).isEqualTo(storeId);
        assertThat(reloaded.mealContractId()).isEqualTo(mealContractId);
        assertThat(reloaded.entrySource()).isEqualTo(EntrySource.PARTNER_MOBILE);
        assertThat(reloaded.amount()).isEqualTo(12_000);
        assertThat(reloaded.createdAt()).isEqualTo(createdAt);
        assertThat(reloaded.status()).isEqualTo(MealUsageStatus.PENDING);
        assertThat(reloaded.version()).isZero();
    }

    @Test
    void returnsEmptyWhenMealUsageDoesNotExist() {
        MealUsageId missingId = new MealUsageId(UUID.fromString("8ef1f7ac-d4bc-4a0d-a8de-3bf2e36eeeca"));

        assertThat(mealUsageRepository.findById(missingId)).isEmpty();
    }

    @Test
    @Transactional
    void savesAndReloadsConfirmedMealUsageWithItsDatabaseVersion() {
        MealUsage pending = pendingUsage("7693bfcf-01b4-4de2-9c9f-5d81612460e5");
        mealUsageRepository.save(pending);
        entityManager.flush();
        entityManager.clear();

        MealUsage confirmed = mealUsageRepository.findById(pending.id()).orElseThrow();
        Confirmation confirmation = new Confirmation("HK", Instant.parse("2026-08-05T09:15:30Z"));
        PrepaidAllocation allocation = new PrepaidAllocation(12_000, 5_000, 7_000, 0);
        confirmed.confirm(confirmation.staffInitials(), confirmation.confirmedAt(), allocation);

        MealUsage saved = mealUsageRepository.save(confirmed);
        assertThat(saved.version()).isEqualTo(1);
        entityManager.clear();

        MealUsage reloaded = mealUsageRepository.findById(pending.id()).orElseThrow();

        assertThat(reloaded.status()).isEqualTo(MealUsageStatus.CONFIRMED);
        assertThat(reloaded.storeId()).isEqualTo(pending.storeId());
        assertThat(reloaded.mealContractId()).isEqualTo(pending.mealContractId());
        assertThat(reloaded.amount()).isEqualTo(12_000);
        assertThat(reloaded.createdAt()).isEqualTo(pending.createdAt());
        assertThat(reloaded.confirmation()).contains(confirmation);
        assertThat(reloaded.prepaidAllocation()).contains(allocation);
        assertThat(reloaded.version()).isEqualTo(saved.version());
    }

    @Test
    void rejectsStaleConfirmedMealUsage() {
        MealUsage pending = transactionTemplate.execute(status -> mealUsageRepository.save(
            pendingUsage("05c1d74d-d51b-4619-a4cf-1b4b17f46e20")
        ));
        MealUsage first = transactionTemplate.execute(status -> mealUsageRepository.findById(pending.id())
            .orElseThrow());
        MealUsage stale = transactionTemplate.execute(status -> mealUsageRepository.findById(pending.id())
            .orElseThrow());

        first.confirm(
            "HK", Instant.parse("2026-08-05T09:15:30Z"), new PrepaidAllocation(12_000, 12_000, 0, 0)
        );
        MealUsage updated = transactionTemplate.execute(status -> mealUsageRepository.save(first));
        stale.confirm(
            "JS", Instant.parse("2026-08-05T09:16:30Z"), new PrepaidAllocation(12_000, 12_000, 0, 0)
        );

        assertThat(updated.version()).isEqualTo(1);
        assertThatThrownBy(() -> transactionTemplate.execute(status -> mealUsageRepository.save(stale)))
            .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    private MealUsage pendingUsage(String id) {
        return MealUsage.pending(
            new MealUsageId(UUID.fromString(id)),
            new StoreId(UUID.fromString("9d5e37dd-dbe2-40dc-97fb-8e77c89aa4cb")),
            new MealContractId(UUID.fromString("019c0f9c-6d58-7d37-b0e3-1af21f7124b9")),
            EntrySource.STORE_TABLET,
            12_000,
            Instant.parse("2026-08-05T09:14:30Z")
        );
    }
}
