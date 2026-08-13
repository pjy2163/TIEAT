package com.tieat.qr.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tieat.ledger.application.ConfirmMealUsageCommand;
import com.tieat.ledger.application.ConfirmMealUsageUseCase;
import com.tieat.ledger.application.RejectMealUsageCommand;
import com.tieat.ledger.application.RejectMealUsageUseCase;
import com.tieat.ledger.domain.MealUsageId;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
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
class MealUsageQrOperationsLifecycleIntegrationTest {

    private static final StoreId STORE_ID = new StoreId(UUID.fromString("9d5e37dd-dbe2-40dc-97fb-8e77c89aa4cb"));
    private static final String OPERATOR_ID = "owner-parang";

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
    private RejectMealUsageUseCase rejectMealUsageUseCase;

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
    void issuesReissuesAndRevokesWithoutPuttingTheRawTokenInTheAudit() throws Exception {
        Fixture fixture = activeFixture();

        var first = operations.issue(new ManageMealUsageQrOperationsUseCase.IssueCommand(
            STORE_ID, "강남점", OPERATOR_ID
        ));

        assertThat(first.rawToken()).matches("[A-Za-z0-9_-]{43}");
        assertThat(jdbcTemplate.queryForObject(
            "select token_hash from meal_usage_qr_contexts where id = ?", String.class, first.context().id().value()
        )).isEqualTo(MealUsageQrToken.sha256Hash(first.rawToken()));
        assertThat(auditColumns()).doesNotContain("raw_token", "token", "token_hash", "qr_url");
        assertThat(jdbcTemplate.queryForList(
            "select operator_id from meal_usage_qr_operation_audits", String.class
        )).doesNotContain(first.rawToken(), MealUsageQrToken.sha256Hash(first.rawToken()));
        assertThat(jdbcTemplate.queryForObject(
            """
                select count(*)
                from meal_usage_qr_operation_audits
                where action = 'QR_ISSUED' and operator_id = ? and store_id = ? and qr_context_id = ?
                  and occurred_at is not null
                """,
            Long.class,
            OPERATOR_ID,
            STORE_ID.value(),
            first.context().id().value()
        )).isEqualTo(1);

        mockMvc.perform(get(publicPath(first.rawToken())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.storeDisplayName").value("강남점"))
            .andExpect(jsonPath("$.partners[0].mealContractId").value(fixture.contract().id().value().toString()));
        mockMvc.perform(publicCreate(first.rawToken(), fixture.contract().id().value(), 8_500))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"));

        var second = operations.reissue(new ManageMealUsageQrOperationsUseCase.ReissueCommand(STORE_ID, OPERATOR_ID));

        assertThat(second.rawToken()).isNotEqualTo(first.rawToken());
        mockMvc.perform(get(publicPath(first.rawToken())))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("PUBLIC_MEAL_USAGE_QR_NOT_FOUND"));
        mockMvc.perform(publicCreate(first.rawToken(), fixture.contract().id().value(), 8_500))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("PUBLIC_MEAL_USAGE_QR_NOT_FOUND"));
        mockMvc.perform(get(publicPath(second.rawToken())))
            .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("select status from meal_usages", String.class)).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
            "select public_qr_context_id from meal_usages", UUID.class
        )).isEqualTo(first.context().id().value());

        operations.revoke(new ManageMealUsageQrOperationsUseCase.RevokeCommand(STORE_ID, OPERATOR_ID));

        mockMvc.perform(get(publicPath(second.rawToken())))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("PUBLIC_MEAL_USAGE_QR_NOT_FOUND"));
        assertThat(auditActions()).containsExactlyInAnyOrder(
            "QR_ISSUED", "QR_REVOKED", "QR_ISSUED", "QR_REVOKED"
        );
    }

    @Test
    void keepsOldQrPendingUsagesConfirmableAndRejectableAfterReissue() throws Exception {
        Fixture fixture = activeFixture();
        var first = operations.issue(new ManageMealUsageQrOperationsUseCase.IssueCommand(STORE_ID, "강남점", OPERATOR_ID));
        mockMvc.perform(publicCreate(first.rawToken(), fixture.contract().id().value(), 8_500))
            .andExpect(status().isCreated());
        mockMvc.perform(publicCreate(first.rawToken(), fixture.contract().id().value(), 1_000))
            .andExpect(status().isCreated());
        UUID confirmUsageId = jdbcTemplate.queryForObject(
            "select id from meal_usages where amount = ?", UUID.class, 8_500
        );
        UUID rejectUsageId = jdbcTemplate.queryForObject(
            "select id from meal_usages where amount = ?", UUID.class, 1_000
        );

        var replacement = operations.reissue(new ManageMealUsageQrOperationsUseCase.ReissueCommand(STORE_ID, OPERATOR_ID));

        assertThat(confirmMealUsageUseCase.confirm(
            new ConfirmMealUsageCommand(new MealUsageId(confirmUsageId), STORE_ID, "HK")
        ).status()).isEqualTo(MealUsageStatus.CONFIRMED);
        assertThat(rejectMealUsageUseCase.reject(
            new RejectMealUsageCommand(new MealUsageId(rejectUsageId), STORE_ID, "store-hk")
        ).status()).isEqualTo(MealUsageStatus.REJECTED);
        assertThat(jdbcTemplate.queryForList(
            "select public_qr_context_id from meal_usages order by id", UUID.class
        )).containsOnly(first.context().id().value());
        assertThat(jdbcTemplate.queryForObject(
            "select prepaid_balance from meal_contracts where id = ?", Long.class, fixture.contract().id().value()
        )).isEqualTo(1_500);
        mockMvc.perform(get(publicPath(first.rawToken())))
            .andExpect(status().isNotFound());
        mockMvc.perform(get(publicPath(replacement.rawToken())))
            .andExpect(status().isOk());
    }

    @Test
    void rollsBackIssueWhenItsAuditInsertFails() {
        activeFixture();
        jdbcTemplate.execute("""
            create function fail_qr_operation_audit_insert() returns trigger
            language plpgsql
            as $$
            begin
                raise exception 'forced QR audit failure';
            end;
            $$;
            """);
        jdbcTemplate.execute("""
            create trigger tr_fail_qr_operation_audit_insert
            before insert on meal_usage_qr_operation_audits
            for each row execute function fail_qr_operation_audit_insert()
            """);
        try {
            assertThatThrownBy(() -> operations.issue(new ManageMealUsageQrOperationsUseCase.IssueCommand(
                STORE_ID, "강남점", OPERATOR_ID
            ))).isInstanceOf(RuntimeException.class);
        } finally {
            jdbcTemplate.execute("drop trigger if exists tr_fail_qr_operation_audit_insert on meal_usage_qr_operation_audits");
            jdbcTemplate.execute("drop function if exists fail_qr_operation_audit_insert()");
        }

        assertThat(jdbcTemplate.queryForObject("select count(*) from meal_usage_qr_contexts", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from meal_usage_qr_operation_audits", Long.class
        )).isZero();
    }

    @Test
    void rollsBackReissueWithoutRevokingTheCurrentQrWhenItsAuditInsertFails() {
        activeFixture();
        var first = operations.issue(new ManageMealUsageQrOperationsUseCase.IssueCommand(STORE_ID, "강남점", OPERATOR_ID));
        jdbcTemplate.execute("""
            create function fail_qr_operation_audit_reissue() returns trigger
            language plpgsql
            as $$
            begin
                raise exception 'forced QR audit failure';
            end;
            $$;
            """);
        jdbcTemplate.execute("""
            create trigger tr_fail_qr_operation_audit_reissue
            before insert on meal_usage_qr_operation_audits
            for each row execute function fail_qr_operation_audit_reissue()
            """);
        try {
            assertThatThrownBy(() -> operations.reissue(
                new ManageMealUsageQrOperationsUseCase.ReissueCommand(STORE_ID, OPERATOR_ID)
            )).isInstanceOf(RuntimeException.class);
        } finally {
            jdbcTemplate.execute("drop trigger if exists tr_fail_qr_operation_audit_reissue on meal_usage_qr_operation_audits");
            jdbcTemplate.execute("drop function if exists fail_qr_operation_audit_reissue()");
        }

        assertThat(jdbcTemplate.queryForObject("select count(*) from meal_usage_qr_contexts", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "select revoked_at is null from meal_usage_qr_contexts where id = ?", Boolean.class, first.context().id().value()
        )).isTrue();
        assertThat(auditActions()).containsExactly("QR_ISSUED");
    }

    @Test
    void serializesConcurrentIssueRequestsToOneCurrentQr() throws Exception {
        activeFixture();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    try {
                        return operations.issue(new ManageMealUsageQrOperationsUseCase.IssueCommand(
                            STORE_ID, "강남점", OPERATOR_ID
                        )).rawToken();
                    } catch (QrOperationException exception) {
                        return null;
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            long issued = results.stream().map(this::getFuture).filter(token -> token != null).count();
            assertThat(issued).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from meal_usage_qr_contexts where store_id = ? and revoked_at is null", Long.class, STORE_ID.value()
        )).isEqualTo(1);
        assertThat(auditActions()).containsExactly("QR_ISSUED");
    }

    @Test
    void serializesPublicCreationWithReissueAsEitherCommittedPendingOrTokenFreeNotFound() throws Exception {
        Fixture fixture = activeFixture();
        var first = operations.issue(new ManageMealUsageQrOperationsUseCase.IssueCommand(STORE_ID, "강남점", OPERATOR_ID));
        CountDownLatch contextLocked = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<?> lockHolder = executor.submit(() -> transactionTemplate.execute(status -> {
                jdbcTemplate.queryForObject(
                    "select id from meal_usage_qr_contexts where id = ? for update", UUID.class, first.context().id().value()
                );
                contextLocked.countDown();
                await(releaseLock);
                return null;
            }));
            assertThat(contextLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<ManageMealUsageQrOperationsUseCase.IssuedQr> reissue = executor.submit(() -> {
                await(start);
                return operations.reissue(new ManageMealUsageQrOperationsUseCase.ReissueCommand(STORE_ID, OPERATOR_ID));
            });
            Future<org.springframework.test.web.servlet.MvcResult> publicCreate = executor.submit(() -> {
                await(start);
                return mockMvc.perform(publicCreate(first.rawToken(), fixture.contract().id().value(), 8_500)).andReturn();
            });
            start.countDown();
            releaseLock.countDown();

            lockHolder.get(5, TimeUnit.SECONDS);
            var replacement = getFuture(reissue);
            int publicStatus = getFuture(publicCreate).getResponse().getStatus();
            assertThat(publicStatus).isIn(201, 404);
            if (publicStatus == 201) {
                assertThat(jdbcTemplate.queryForObject("select status from meal_usages", String.class)).isEqualTo("PENDING");
                assertThat(jdbcTemplate.queryForObject(
                    "select public_qr_context_id from meal_usages", UUID.class
                )).isEqualTo(first.context().id().value());
            } else {
                assertThat(jdbcTemplate.queryForObject("select count(*) from meal_usages", Long.class)).isZero();
            }
            assertThat(jdbcTemplate.queryForObject(
                "select revoked_at is not null from meal_usage_qr_contexts where id = ?", Boolean.class, first.context().id().value()
            )).isTrue();
            mockMvc.perform(get(publicPath(replacement.rawToken()))).andExpect(status().isOk());
        } finally {
            start.countDown();
            releaseLock.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void requiresReissueForAnExpiredButUnrevokedCurrentQr() {
        Fixture fixture = activeFixture();
        String expiredToken = MealUsageQrToken.generate();
        UUID expiredContextId = UUID.randomUUID();
        Instant issuedAt = Instant.now().minusSeconds(91 * 24 * 60 * 60L);
        jdbcTemplate.update(
            """
                insert into meal_usage_qr_contexts
                    (id, store_id, store_display_name, token_hash, expires_at, revoked_at, created_at)
                values (?, ?, ?, ?, ?, null, ?)
                """,
            expiredContextId,
            STORE_ID.value(),
            "강남점",
            MealUsageQrToken.sha256Hash(expiredToken),
            Timestamp.from(Instant.now().minusSeconds(1)),
            Timestamp.from(issuedAt)
        );

        assertThatThrownBy(() -> operations.issue(new ManageMealUsageQrOperationsUseCase.IssueCommand(
            STORE_ID, "다른 표시명", OPERATOR_ID
        ))).isInstanceOf(QrOperationException.class)
            .hasMessageContaining("use reissue");

        var reissued = operations.reissue(new ManageMealUsageQrOperationsUseCase.ReissueCommand(STORE_ID, OPERATOR_ID));

        assertThat(reissued.context().storeDisplayName()).isEqualTo("강남점");
        assertThat(reissued.context().id().value()).isNotEqualTo(expiredContextId);
        assertThat(jdbcTemplate.queryForObject(
            "select revoked_at is not null from meal_usage_qr_contexts where id = ?", Boolean.class, expiredContextId
        )).isTrue();
        assertThat(fixture.contract().isQrSelectable()).isTrue();
    }

    @Test
    void requiresAnEnabledAccountOnlyForInitialIssueAndKeepsRecoveryAvailableAfterItIsDisabled() {
        activeFixture();
        var first = operations.issue(new ManageMealUsageQrOperationsUseCase.IssueCommand(STORE_ID, "강남점", OPERATOR_ID));
        jdbcTemplate.update("update store_accounts set enabled = false where store_id = ?", STORE_ID.value());

        assertThatThrownBy(() -> operations.issue(new ManageMealUsageQrOperationsUseCase.IssueCommand(
            STORE_ID, "강남점", OPERATOR_ID
        ))).isInstanceOf(QrOperationException.class)
            .hasMessageContaining("No enabled shared store account");
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from meal_usage_qr_contexts", Long.class
        )).isEqualTo(1);

        var replacement = operations.reissue(new ManageMealUsageQrOperationsUseCase.ReissueCommand(STORE_ID, OPERATOR_ID));
        operations.revoke(new ManageMealUsageQrOperationsUseCase.RevokeCommand(STORE_ID, OPERATOR_ID));

        assertThat(replacement.context().id()).isNotEqualTo(first.context().id());
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from meal_usage_qr_contexts where revoked_at is null", Long.class
        )).isZero();
        assertThat(auditActions()).containsExactlyInAnyOrder(
            "QR_ISSUED", "QR_REVOKED", "QR_ISSUED", "QR_REVOKED"
        );
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

    private List<String> auditColumns() {
        return jdbcTemplate.queryForList(
            """
                select column_name
                from information_schema.columns
                where table_schema = 'public' and table_name = 'meal_usage_qr_operation_audits'
                """,
            String.class
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
            .contentType("application/json")
            .content("{\"mealContractId\":\"" + mealContractId + "\",\"customerName\":\"홍길동\",\"amountMinor\":" + amount + "}");
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
