package com.tieat.qr.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tieat.ledger.application.ConfirmMealUsageCommand;
import com.tieat.ledger.application.ConfirmMealUsageUseCase;
import com.tieat.ledger.domain.EntrySource;
import com.tieat.ledger.domain.MealUsage;
import com.tieat.ledger.domain.MealUsageId;
import com.tieat.ledger.domain.MealUsageRepository;
import com.tieat.ledger.domain.MealUsageStatus;
import com.tieat.partnership.domain.MealContract;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.partnership.domain.MealContractPaymentType;
import com.tieat.partnership.domain.MealContractRepository;
import com.tieat.partnership.domain.PartnerOrganization;
import com.tieat.partnership.domain.PartnerOrganizationId;
import com.tieat.partnership.domain.PartnerOrganizationRepository;
import com.tieat.qr.application.ManageMealUsageQrOperationsUseCase;
import com.tieat.qr.application.QrOperationException;
import com.tieat.qr.domain.MealUsageQrToken;
import com.tieat.store.domain.StoreId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MealUsageQrOperationsPartnerSelectionIntegrationTest {

    private static final StoreId STORE_ID = new StoreId(UUID.fromString("9d5e37dd-dbe2-40dc-97fb-8e77c89aa4cb"));
    private static final StoreId OTHER_STORE_ID = new StoreId(UUID.fromString("6142be7d-0dc9-4f77-a17d-07e1e5c6e9a1"));
    private static final String OPERATOR_ID = "owner-parang";
    private static final String PUBLIC_CLIENT_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
        .withDatabaseName("tieat")
        .withUsername("tieat")
        .withPassword("tieat");

    @Autowired
    private ManageMealUsageQrOperationsUseCase operations;

    @Autowired
    private ConfirmMealUsageUseCase confirmMealUsageUseCase;

    @Autowired
    private MealUsageRepository mealUsageRepository;

    @Autowired
    private MealContractRepository mealContractRepository;

    @Autowired
    private PartnerOrganizationRepository partnerOrganizationRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void clearDatabase() {
        jdbcTemplate.update("delete from public_meal_usage_idempotency_keys");
        jdbcTemplate.update("delete from meal_usages");
        jdbcTemplate.update("delete from meal_usage_qr_operation_audits");
        jdbcTemplate.update("delete from meal_usage_qr_contexts");
        jdbcTemplate.update("delete from meal_contracts");
        jdbcTemplate.update("delete from partner_organizations");
        jdbcTemplate.update("delete from store_accounts");
    }

    @Test
    void rollsBackPartnerSelectionWhenItsAuditInsertFails() {
        Fixture fixture = activeFixture();
        jdbcTemplate.execute("""
            create function fail_qr_operation_audit_toggle() returns trigger
            language plpgsql
            as $$
            begin
                raise exception 'forced QR audit failure';
            end;
            $$;
            """);
        jdbcTemplate.execute("""
            create trigger tr_fail_qr_operation_audit_toggle
            before insert on meal_usage_qr_operation_audits
            for each row execute function fail_qr_operation_audit_toggle()
            """);
        try {
            assertThatThrownBy(() -> operations.changePartnerSelection(
                new ManageMealUsageQrOperationsUseCase.ChangePartnerSelectionCommand(
                    STORE_ID, fixture.contract().id(), false, OPERATOR_ID
                )
            )).isInstanceOf(RuntimeException.class);
        } finally {
            jdbcTemplate.execute("drop trigger if exists tr_fail_qr_operation_audit_toggle on meal_usage_qr_operation_audits");
            jdbcTemplate.execute("drop function if exists fail_qr_operation_audit_toggle()");
        }

        assertThat(jdbcTemplate.queryForObject(
            "select qr_selectable from meal_contracts where id = ?", Boolean.class, fixture.contract().id().value()
        )).isTrue();
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from meal_usage_qr_operation_audits", Long.class
        )).isZero();
    }

    @Test
    void serializesPublicCreationWithPartnerDisableAsEitherCommittedPendingOrTokenFreeNotFound() throws Exception {
        Fixture fixture = activeFixture();
        qrSelectablePostpaidContract(STORE_ID, partner("협력사 B").id());
        var issued = operations.issue(new ManageMealUsageQrOperationsUseCase.IssueCommand(STORE_ID, "강남점", OPERATOR_ID));
        CountDownLatch contractLocked = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<?> lockHolder = executor.submit(() -> transactionTemplate.execute(status -> {
                mealContractRepository.findByIdForUpdate(fixture.contract().id()).orElseThrow();
                contractLocked.countDown();
                await(releaseLock);
                return null;
            }));
            assertThat(contractLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<ManageMealUsageQrOperationsUseCase.PartnerSelectionResult> disable = executor.submit(() -> {
                await(start);
                return operations.changePartnerSelection(new ManageMealUsageQrOperationsUseCase.ChangePartnerSelectionCommand(
                    STORE_ID, fixture.contract().id(), false, OPERATOR_ID
                ));
            });
            Future<org.springframework.test.web.servlet.MvcResult> publicCreate = executor.submit(() -> {
                await(start);
                return mockMvc.perform(publicCreate(issued.rawToken(), fixture.contract().id().value(), 8_500)).andReturn();
            });
            start.countDown();
            releaseLock.countDown();

            lockHolder.get(5, TimeUnit.SECONDS);
            assertThat(getFuture(disable).changed()).isTrue();
            int publicStatus = getFuture(publicCreate).getResponse().getStatus();
            assertThat(publicStatus).isIn(201, 404);
            if (publicStatus == 201) {
                assertThat(jdbcTemplate.queryForObject("select status from meal_usages", String.class)).isEqualTo("PENDING");
            } else {
                assertThat(jdbcTemplate.queryForObject("select count(*) from meal_usages", Long.class)).isZero();
            }
        } finally {
            start.countDown();
            releaseLock.countDown();
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
            "select qr_selectable from meal_contracts where id = ?", Boolean.class, fixture.contract().id().value()
        )).isFalse();
    }

    @Test
    void changesPartnerSelectionWithinStoreAndProtectsTheLastPartnerWhileQrIsCurrent() throws Exception {
        Fixture fixture = activeFixture();
        MealContract secondContract = qrSelectablePostpaidContract(STORE_ID, partner("협력사 B").id());
        MealContract otherStoreContract = qrSelectablePostpaidContract(OTHER_STORE_ID, partner("다른 매장 협력사").id());
        var issued = operations.issue(new ManageMealUsageQrOperationsUseCase.IssueCommand(STORE_ID, "강남점", OPERATOR_ID));

        var changed = operations.changePartnerSelection(new ManageMealUsageQrOperationsUseCase.ChangePartnerSelectionCommand(
            STORE_ID, fixture.contract().id(), false, OPERATOR_ID
        ));
        var unchanged = operations.changePartnerSelection(new ManageMealUsageQrOperationsUseCase.ChangePartnerSelectionCommand(
            STORE_ID, fixture.contract().id(), false, OPERATOR_ID
        ));

        assertThat(changed.changed()).isTrue();
        assertThat(unchanged.changed()).isFalse();
        assertThat(operations.partnerSelections(STORE_ID))
            .extracting(selection -> selection.mealContractId().value(), selection -> selection.qrSelectable())
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(fixture.contract().id().value(), false),
                org.assertj.core.groups.Tuple.tuple(secondContract.id().value(), true)
            );
        mockMvc.perform(get(publicPath(issued.rawToken())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.partners.length()").value(1))
            .andExpect(jsonPath("$.partners[0].mealContractId").value(secondContract.id().value().toString()));
        mockMvc.perform(publicCreate(issued.rawToken(), fixture.contract().id().value(), 8_500))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("PUBLIC_MEAL_USAGE_QR_NOT_FOUND"));
        assertThat(jdbcTemplate.queryForObject(
            """
                select qr_selectable_before
                from meal_usage_qr_operation_audits
                where action = 'PARTNER_QR_DISABLED' and meal_contract_id = ?
                """,
            Boolean.class,
            fixture.contract().id().value()
        )).isTrue();
        assertThat(jdbcTemplate.queryForObject(
            """
                select qr_selectable_after
                from meal_usage_qr_operation_audits
                where action = 'PARTNER_QR_DISABLED' and meal_contract_id = ?
                """,
            Boolean.class,
            fixture.contract().id().value()
        )).isFalse();
        assertThatThrownBy(() -> operations.changePartnerSelection(
            new ManageMealUsageQrOperationsUseCase.ChangePartnerSelectionCommand(STORE_ID, secondContract.id(), false, OPERATOR_ID)
        )).isInstanceOf(QrOperationException.class)
            .hasMessageContaining("Revoke the current QR");
        assertThatThrownBy(() -> operations.changePartnerSelection(
            new ManageMealUsageQrOperationsUseCase.ChangePartnerSelectionCommand(STORE_ID, otherStoreContract.id(), false, OPERATOR_ID)
        )).isInstanceOf(QrOperationException.class)
            .hasMessageContaining("not found for this store");
        assertThat(auditActions()).containsExactlyInAnyOrder("QR_ISSUED", "PARTNER_QR_DISABLED");
    }

    @Test
    void serializesPartnerToggleWithConfirmationWithoutOverwritingThePrepaidBalance() throws Exception {
        Fixture fixture = activeFixture();
        qrSelectablePostpaidContract(STORE_ID, partner("협력사 B").id());
        operations.issue(new ManageMealUsageQrOperationsUseCase.IssueCommand(STORE_ID, "강남점", OPERATOR_ID));
        MealUsage usage = mealUsageRepository.save(MealUsage.pending(
            new MealUsageId(UUID.randomUUID()),
            STORE_ID,
            fixture.contract().id(),
            EntrySource.STORE_TABLET,
            8_000,
            Instant.now()
        ));

        CountDownLatch contractLocked = new CountDownLatch(1);
        CountDownLatch releaseConfirmation = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> confirmation = executor.submit(() -> transactionTemplate.execute(status -> {
                mealContractRepository.findByIdForUpdate(fixture.contract().id()).orElseThrow();
                contractLocked.countDown();
                await(releaseConfirmation);
                return confirmMealUsageUseCase.confirm(new ConfirmMealUsageCommand(usage.id(), STORE_ID, "HK"));
            }));
            assertThat(contractLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> toggle = executor.submit(() -> operations.changePartnerSelection(
                new ManageMealUsageQrOperationsUseCase.ChangePartnerSelectionCommand(
                    STORE_ID, fixture.contract().id(), false, OPERATOR_ID
                )
            ));
            assertThat(toggle.isDone()).isFalse();

            releaseConfirmation.countDown();
            confirmation.get(5, TimeUnit.SECONDS);
            toggle.get(5, TimeUnit.SECONDS);
        } finally {
            releaseConfirmation.countDown();
            executor.shutdownNow();
        }

        assertThat(reloadUsage(usage.id()).status()).isEqualTo(MealUsageStatus.CONFIRMED);
        assertThat(jdbcTemplate.queryForObject(
            "select prepaid_balance from meal_contracts where id = ?", Long.class, fixture.contract().id().value()
        )).isEqualTo(2_000);
        assertThat(jdbcTemplate.queryForObject(
            "select qr_selectable from meal_contracts where id = ?", Boolean.class, fixture.contract().id().value()
        )).isFalse();
    }

    private Fixture activeFixture() {
        seedStoreAccount(STORE_ID);
        PartnerOrganization partner = partner("협력사 A");
        MealContract contract = mealContractRepository.save(new MealContract(
            new MealContractId(UUID.randomUUID()),
            STORE_ID,
            MealContractPaymentType.PREPAID_WITH_RECEIVABLE_OVERFLOW,
            10_000,
            partner.id(),
            true
        ));
        return new Fixture(contract);
    }

    private MealContract qrSelectablePostpaidContract(StoreId storeId, PartnerOrganizationId partnerOrganizationId) {
        return mealContractRepository.save(new MealContract(
            new MealContractId(UUID.randomUUID()),
            storeId,
            MealContractPaymentType.POSTPAID,
            0,
            partnerOrganizationId,
            true
        ));
    }

    private PartnerOrganization partner(String displayName) {
        return partnerOrganizationRepository.save(new PartnerOrganization(
            new PartnerOrganizationId(UUID.randomUUID()), displayName
        ));
    }

    private void seedStoreAccount(StoreId storeId) {
        jdbcTemplate.update(
            "insert into store_accounts (login_id, password_hash, store_id, enabled) values (?, ?, ?, true)",
            "store-" + storeId.value(),
            "test-password-hash",
            storeId.value()
        );
    }

    private List<String> auditActions() {
        return jdbcTemplate.queryForList(
            "select action from meal_usage_qr_operation_audits", String.class
        );
    }

    private String publicPath(String rawToken) {
        return "/api/v1/public/meal-usage-qr/" + rawToken;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder publicCreate(
        String rawToken,
        UUID mealContractId,
        long amount
    ) {
        return post(publicPath(rawToken) + "/meal-usages")
            .header("Idempotency-Key", UUID.randomUUID())
            .header("Public-Request-Key", MealUsageQrToken.generate())
            .header("Public-Client-Key", PUBLIC_CLIENT_KEY)
            .contentType("application/json")
            .content("{\"mealContractId\":\"" + mealContractId + "\",\"customerName\":\"홍길동\",\"amountMinor\":" + amount + "}");
    }

    private MealUsage reloadUsage(MealUsageId id) {
        return transactionTemplate.execute(status -> mealUsageRepository.findById(id).orElseThrow());
    }

    private <T> T getFuture(Future<T> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("QR operations request did not finish", exception);
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out coordinating QR operations test");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted coordinating QR operations test", exception);
        }
    }

    private record Fixture(MealContract contract) {
    }
}
