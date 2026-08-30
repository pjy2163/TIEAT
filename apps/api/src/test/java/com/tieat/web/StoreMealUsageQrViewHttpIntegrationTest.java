package com.tieat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tieat.partnership.domain.MealContract;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.partnership.domain.MealContractPaymentType;
import com.tieat.partnership.domain.MealContractRepository;
import com.tieat.partnership.domain.PartnerOrganization;
import com.tieat.partnership.domain.PartnerOrganizationId;
import com.tieat.partnership.domain.PartnerOrganizationRepository;
import com.tieat.qr.application.ManageMealUsageQrOperationsUseCase;
import com.tieat.qr.domain.MealUsageQrContext;
import com.tieat.qr.domain.MealUsageQrToken;
import com.tieat.store.domain.StoreId;
import com.tieat.web.StoreOnboardingHttpIntegrationSupport.SessionHandle;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = StorePartnerContextHttpIntegrationTest.R032TestApplication.class)
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
    "spring.servlet.multipart.max-file-size=10MB",
    "spring.servlet.multipart.max-request-size=11MB"
})
class StoreMealUsageQrViewHttpIntegrationTest {

    private static final String QR_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String PASSWORD = "correct-password";
    private static final StoreId STORE_A = new StoreId(UUID.fromString("9d5e37dd-dbe2-40dc-97fb-8e77c89aa4cb"));
    private static final StoreId STORE_B = new StoreId(UUID.fromString("f9d5f9aa-c0e3-470a-a9ef-9d7c1cd1ae31"));

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
        .withDatabaseName("tieat")
        .withUsername("tieat")
        .withPassword("tieat");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MealContractRepository mealContractRepository;

    @Autowired
    private PartnerOrganizationRepository partnerOrganizationRepository;

    @Autowired
    private ManageMealUsageQrOperationsUseCase operations;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("tieat.qr.token-encryption-key", () -> QR_KEY);
        registry.add("tieat.qr.token-encryption-key-version", () -> "1");
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
    void returnsAStableStoreScopedPathWithNoStoreAndNoRawTokenInPersistence() throws Exception {
        Fixture storeA = activeFixture("qr-view-a", STORE_A, "매장 A");
        Fixture storeB = activeFixture("qr-view-b", STORE_B, "매장 B");

        JsonNode first = json(mockMvc.perform(get("/api/v1/store-meal-usage-qr").cookie(storeA.session.cookie()))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
            .andReturn()).body();
        JsonNode second = json(mockMvc.perform(get("/api/v1/store-meal-usage-qr").cookie(storeA.session.cookie()))
            .andExpect(status().isOk())
            .andReturn()).body();
        JsonNode other = json(mockMvc.perform(get("/api/v1/store-meal-usage-qr").cookie(storeB.session.cookie()))
            .andExpect(status().isOk())
            .andReturn()).body();

        assertThat(first.get("status").asText()).isEqualTo("AVAILABLE");
        assertThat(first.get("publicPath").asText()).isEqualTo("/qr/" + storeA.issued.rawToken());
        assertThat(second.get("publicPath").asText()).isEqualTo(first.get("publicPath").asText());
        assertThat(other.get("publicPath").asText()).isEqualTo("/qr/" + storeB.issued.rawToken());
        assertThat(other.get("publicPath").asText()).isNotEqualTo(first.get("publicPath").asText());

        byte[] ciphertext = jdbcTemplate.queryForObject(
            "select token_ciphertext from meal_usage_qr_contexts where id = ?",
            byte[].class,
            storeA.issued.context().id().value()
        );
        String tokenHash = jdbcTemplate.queryForObject(
            "select token_hash from meal_usage_qr_contexts where id = ?",
            String.class,
            storeA.issued.context().id().value()
        );
        assertThat(ciphertext).isNotNull();
        assertThat(ciphertext).isNotEqualTo(storeA.issued.rawToken().getBytes(StandardCharsets.UTF_8));
        assertThat(tokenHash).isEqualTo(MealUsageQrToken.sha256Hash(storeA.issued.rawToken()));
        assertThat(jdbcTemplate.queryForObject(
            "select token_nonce from meal_usage_qr_contexts where id = ?",
            byte[].class,
            storeA.issued.context().id().value()
        )).hasSize(12);
    }

    @Test
    void requiresAuthenticationAndDoesNotAcceptAStoreIdParameter() throws Exception {
        activeFixture("qr-view-auth", STORE_A, "매장 A");

        mockMvc.perform(get("/api/v1/store-meal-usage-qr").param("storeId", STORE_B.value().toString()))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void reportsLegacyExpiredAndMissingCurrentStatesWithoutReissuing() throws Exception {
        Account account = account("qr-view-status", STORE_A);
        SessionHandle session = authenticate(account.loginId());
        UUID contextId = UUID.randomUUID();
        String legacyToken = MealUsageQrToken.generate();
        Instant issuedAt = Instant.parse("2026-08-10T00:00:00Z");
        jdbcTemplate.update(
            """
                insert into meal_usage_qr_contexts
                    (id, store_id, store_display_name, token_hash, expires_at, revoked_at, created_at)
                values (?, ?, ?, ?, ?, null, ?)
                """,
            contextId,
            STORE_A.value(),
            "매장 A",
            MealUsageQrToken.sha256Hash(legacyToken),
            Timestamp.from(Instant.now().plusSeconds(60)),
            Timestamp.from(issuedAt)
        );

        JsonNode legacy = json(mockMvc.perform(get("/api/v1/store-meal-usage-qr").cookie(session.cookie()))
            .andExpect(status().isOk()).andReturn()).body();
        assertThat(legacy.get("status").asText()).isEqualTo("REISSUE_REQUIRED");
        assertThat(legacy.get("publicPath").isNull()).isTrue();

        jdbcTemplate.update(
            "update meal_usage_qr_contexts set expires_at = ? where id = ?",
            Timestamp.from(Instant.now().minusSeconds(1)), contextId
        );
        JsonNode expired = json(mockMvc.perform(get("/api/v1/store-meal-usage-qr").cookie(session.cookie()))
            .andExpect(status().isOk()).andReturn()).body();
        assertThat(expired.get("status").asText()).isEqualTo("EXPIRED");
        assertThat(expired.get("publicPath").isNull()).isTrue();

        jdbcTemplate.update("delete from meal_usage_qr_contexts where id = ?", contextId);
        JsonNode missing = json(mockMvc.perform(get("/api/v1/store-meal-usage-qr").cookie(session.cookie()))
            .andExpect(status().isOk()).andReturn()).body();
        assertThat(missing.get("status").asText()).isEqualTo("NOT_AVAILABLE");
    }

    @Test
    void protectsQrRenewalWithAuthenticationAndCsrfWithoutCaching() throws Exception {
        Fixture fixture = activeFixture("qr-view-renew-security", STORE_A, "매장 A");

        mockMvc.perform(post("/api/v1/store-meal-usage-qr/renewals"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(post("/api/v1/store-meal-usage-qr/renewals").cookie(fixture.session.cookie()))
            .andExpect(status().isForbidden())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.errorCode").value("CSRF_TOKEN_INVALID"));
        mockMvc.perform(post("/api/v1/store-meal-usage-qr/renewals")
                .cookie(fixture.session.cookie())
                .header("X-CSRF-TOKEN", "invalid-csrf-token"))
            .andExpect(status().isForbidden())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.errorCode").value("CSRF_TOKEN_INVALID"));
    }

    @Test
    void renewsExpiredQrWithAuthenticatedCsrfAndRejectsNonExpiredRenewal() throws Exception {
        Fixture fixture = activeFixture("qr-view-renew-expired", STORE_A, "매장 A");
        Fixture otherStore = activeFixture("qr-view-renew-other", STORE_B, "매장 B");
        UUID oldContextId = fixture.issued.context().id().value();
        jdbcTemplate.update(
            "update meal_usage_qr_contexts set created_at = ?, expires_at = ? where id = ?",
            Timestamp.from(Instant.now().minusSeconds(2)),
            Timestamp.from(Instant.now().minusSeconds(1)),
            oldContextId
        );

        MvcResult renewedResult = mockMvc.perform(post("/api/v1/store-meal-usage-qr/renewals")
                .cookie(fixture.session.cookie())
                .header("X-CSRF-TOKEN", fixture.session.csrfToken()))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.status").value("AVAILABLE"))
            .andExpect(jsonPath("$.publicPath").value(org.hamcrest.Matchers.startsWith("/qr/")))
            .andExpect(jsonPath("$.issuedAt").isNotEmpty())
            .andExpect(jsonPath("$.expiresAt").isNotEmpty())
            .andReturn();
        JsonNode renewed = json(renewedResult).body();
        String newPath = renewed.get("publicPath").asText();
        String newToken = newPath.substring("/qr/".length());
        Instant issuedAt = Instant.parse(renewed.get("issuedAt").asText());
        Instant expiresAt = Instant.parse(renewed.get("expiresAt").asText());

        assertThat(MealUsageQrToken.isValid(newToken)).isTrue();
        assertThat(expiresAt).isEqualTo(issuedAt.plus(MealUsageQrContext.DEFAULT_LIFETIME));
        assertThat(jdbcTemplate.queryForObject(
            "select revoked_at is not null from meal_usage_qr_contexts where id = ?",
            Boolean.class,
            oldContextId
        )).isTrue();
        assertThat(jdbcTemplate.queryForObject(
            "select revoked_at is null from meal_usage_qr_contexts where id = ?",
            Boolean.class,
            otherStore.issued.context().id().value()
        )).isTrue();

        mockMvc.perform(get("/api/v1/public/meal-usage-qr/" + fixture.issued.rawToken()))
            .andExpect(status().isNotFound())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.errorCode").value("PUBLIC_MEAL_USAGE_QR_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/public/meal-usage-qr/" + newToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.storeDisplayName").value("매장 A"))
            .andExpect(jsonPath("$.qrExpiresAt").value(expiresAt.toString()));

        mockMvc.perform(post("/api/v1/store-meal-usage-qr/renewals")
                .cookie(fixture.session.cookie())
                .header("X-CSRF-TOKEN", fixture.session.csrfToken()))
            .andExpect(status().isConflict())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.errorCode").value("STORE_MEAL_USAGE_QR_RENEWAL_NOT_ALLOWED"));
    }

    @Test
    void rejectsExpiredQrRenewalWithoutSelectableContractWithoutChangingContextOrAudit() throws Exception {
        Fixture fixture = activeFixture("qr-view-renew-no-partner", STORE_A, "매장 A");
        UUID contextId = fixture.issued.context().id().value();
        String tokenHash = jdbcTemplate.queryForObject(
            "select token_hash from meal_usage_qr_contexts where id = ?", String.class, contextId
        );
        long contextCount = jdbcTemplate.queryForObject("select count(*) from meal_usage_qr_contexts", Long.class);
        long auditCount = jdbcTemplate.queryForObject("select count(*) from meal_usage_qr_operation_audits", Long.class);
        Timestamp createdAt = Timestamp.from(Instant.now().minusSeconds(2));
        Timestamp expiredAt = Timestamp.from(Instant.now().minusSeconds(1));
        jdbcTemplate.update(
            "update meal_usage_qr_contexts set created_at = ?, expires_at = ? where id = ?",
            createdAt,
            expiredAt,
            contextId
        );
        jdbcTemplate.update("update meal_contracts set qr_selectable = false where id = ?", fixture.contract.id().value());

        mockMvc.perform(post("/api/v1/store-meal-usage-qr/renewals")
                .cookie(fixture.session.cookie())
                .header("X-CSRF-TOKEN", fixture.session.csrfToken()))
            .andExpect(status().isConflict())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.errorCode").value("STORE_MEAL_USAGE_QR_RENEWAL_NOT_ALLOWED"));

        assertThat(jdbcTemplate.queryForObject("select count(*) from meal_usage_qr_contexts", Long.class))
            .isEqualTo(contextCount);
        assertThat(jdbcTemplate.queryForObject(
            "select token_hash from meal_usage_qr_contexts where id = ?", String.class, contextId
        )).isEqualTo(tokenHash);
        assertThat(jdbcTemplate.queryForObject(
            "select revoked_at from meal_usage_qr_contexts where id = ?", Timestamp.class, contextId
        )).isNull();
        assertThat(jdbcTemplate.queryForObject(
            "select expires_at from meal_usage_qr_contexts where id = ?", Timestamp.class, contextId
        )).isEqualTo(expiredAt);
        assertThat(jdbcTemplate.queryForObject("select count(*) from meal_usage_qr_operation_audits", Long.class))
            .isEqualTo(auditCount);
    }

    @Test
    void reportsReissueRequiredForTamperedCiphertext() throws Exception {
        Fixture fixture = activeFixture("qr-view-tampered", STORE_A, "매장 A");
        byte[] tampered = jdbcTemplate.queryForObject(
            "select token_ciphertext from meal_usage_qr_contexts where id = ?",
            byte[].class,
            fixture.issued.context().id().value()
        );
        tampered[0] ^= 1;
        jdbcTemplate.update(
            "update meal_usage_qr_contexts set token_ciphertext = ? where id = ?",
            tampered,
            fixture.issued.context().id().value()
        );

        JsonNode response = json(mockMvc.perform(get("/api/v1/store-meal-usage-qr").cookie(fixture.session.cookie()))
            .andExpect(status().isOk()).andReturn()).body();
        assertThat(response.get("status").asText()).isEqualTo("REISSUE_REQUIRED");
        assertThat(response.get("publicPath").isNull()).isTrue();
    }

    @Test
    void rollsBackIssueWhenProtectionAuditPersistenceFails() {
        account("qr-view-rollback-issue", STORE_A);
        jdbcTemplate.execute(failingAuditFunction("qr_view_fail_issue"));
        jdbcTemplate.execute(failingAuditTrigger("qr_view_fail_issue"));
        try {
            assertThatThrownBy(() -> operations.issue(new ManageMealUsageQrOperationsUseCase.IssueCommand(
                STORE_A, "매장 A", "qr-view-rollback-issue"
            ))).isInstanceOf(RuntimeException.class);
        } finally {
            dropFailingAuditTrigger("qr_view_fail_issue");
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from meal_usage_qr_contexts", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from meal_usage_qr_operation_audits", Long.class)).isZero();
    }

    @Test
    void rollsBackReissueWhenProtectionAuditPersistenceFails() throws Exception {
        Fixture fixture = activeFixture("qr-view-rollback-reissue", STORE_A, "매장 A");
        jdbcTemplate.execute(failingAuditFunction("qr_view_fail_reissue"));
        jdbcTemplate.execute(failingAuditTrigger("qr_view_fail_reissue"));
        try {
            assertThatThrownBy(() -> operations.reissue(new ManageMealUsageQrOperationsUseCase.ReissueCommand(
                STORE_A, "qr-view-rollback-reissue"
            ))).isInstanceOf(RuntimeException.class);
        } finally {
            dropFailingAuditTrigger("qr_view_fail_reissue");
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from meal_usage_qr_contexts", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "select revoked_at is null from meal_usage_qr_contexts where id = ?",
            Boolean.class,
            fixture.issued.context().id().value()
        )).isTrue();
        assertThat(jdbcTemplate.queryForObject("select count(*) from meal_usage_qr_operation_audits", Long.class)).isEqualTo(1);
    }

    private Fixture activeFixture(String loginId, StoreId storeId, String storeName) throws Exception {
        Account account = account(loginId, storeId);
        PartnerOrganization partner = partnerOrganizationRepository.save(new PartnerOrganization(
            new PartnerOrganizationId(UUID.randomUUID()), "협력사 " + loginId
        ));
        MealContract contract = mealContractRepository.save(new MealContract(
            new MealContractId(UUID.randomUUID()),
            storeId,
            MealContractPaymentType.PREPAID_WITH_RECEIVABLE_OVERFLOW,
            0,
            partner.id(),
            true
        ));
        SessionHandle session = authenticate(account.loginId());
        ManageMealUsageQrOperationsUseCase.IssuedQr issued = operations.issue(
            new ManageMealUsageQrOperationsUseCase.IssueCommand(storeId, storeName, account.loginId())
        );
        return new Fixture(account, session, contract, issued);
    }

    private Account account(String loginId, StoreId storeId) {
        jdbcTemplate.update(
            "insert into store_accounts (login_id, password_hash, store_id, enabled) values (?, ?, ?, true)",
            loginId,
            passwordEncoder.encode(PASSWORD),
            storeId.value()
        );
        return new Account(loginId, storeId);
    }

    private SessionHandle authenticate(String loginId) throws Exception {
        return StoreOnboardingHttpIntegrationSupport.authenticatedSession(
            mockMvc, objectMapper, loginId, PASSWORD
        );
    }

    private JsonResult json(MvcResult result) throws Exception {
        return new JsonResult(result, objectMapper.readTree(result.getResponse().getContentAsString()));
    }

    private String failingAuditFunction(String name) {
        return """
            create function %s() returns trigger
            language plpgsql
            as $$
            begin
                raise exception 'forced QR audit failure';
            end;
            $$;
            """.formatted(name);
    }

    private String failingAuditTrigger(String name) {
        return """
            create trigger tr_%s
            before insert on meal_usage_qr_operation_audits
            for each row execute function %s()
            """.formatted(name, name);
    }

    private void dropFailingAuditTrigger(String name) {
        jdbcTemplate.execute("drop trigger if exists tr_" + name + " on meal_usage_qr_operation_audits");
        jdbcTemplate.execute("drop function if exists " + name + "()");
    }

    private record Account(String loginId, StoreId storeId) {
    }

    private record Fixture(
        Account account,
        SessionHandle session,
        MealContract contract,
        ManageMealUsageQrOperationsUseCase.IssuedQr issued
    ) {
    }

    private record JsonResult(MvcResult result, JsonNode body) {
    }

}
