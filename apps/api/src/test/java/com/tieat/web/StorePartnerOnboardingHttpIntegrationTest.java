package com.tieat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tieat.web.StoreOnboardingHttpIntegrationSupport.SessionHandle;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class StorePartnerOnboardingHttpIntegrationTest {

    private static final String QR_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("tieat.onboarding.invite-code", () -> StoreOnboardingHttpIntegrationSupport.INVITE_CODE);
        registry.add("tieat.qr.token-encryption-key", () -> QR_KEY);
    }

    @BeforeEach
    void clearDatabase() {
        jdbcTemplate.execute("""
            truncate table meal_usage_qr_operation_audits, meal_usage_qr_contexts,
                store_partner_registrations, stores, store_catalog_entries, store_accounts,
                partner_organizations, meal_contracts, meal_usages
            restart identity cascade
            """);
    }

    @Test
    void registersPostpaidFirstPartnerCompletesOnboardingAndReplaysWithoutADuplicate() throws Exception {
        SessionHandle session = StoreOnboardingHttpIntegrationSupport.signUpManualStore(
            mockMvc, objectMapper, "postpaid-store", "correct-password", "후불 가게"
        );
        String csrfToken = StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, session);
        String body = partnerBody("협력사 A", "POSTPAID", 0, true);

        mockMvc.perform(StoreOnboardingHttpIntegrationSupport.partnerRequest(session, csrfToken, body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.onboardingStatus").value("COMPLETE"))
            .andExpect(jsonPath("$.created").value(true))
            .andExpect(jsonPath("$.partnerDisplayName").value("협력사 A"))
            .andExpect(jsonPath("$.partnerKind").value("ORGANIZATION"))
            .andExpect(jsonPath("$.mealContractId").isNotEmpty())
            .andExpect(jsonPath("$.paymentType").value("POSTPAID"));
        mockMvc.perform(StoreOnboardingHttpIntegrationSupport.partnerRequest(session, csrfToken, body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.onboardingStatus").value("COMPLETE"))
            .andExpect(jsonPath("$.created").value(false));

        UUID storeId = storeIdFor("postpaid-store");
        assertThat(jdbcTemplate.queryForObject("select count(*) from partner_organizations", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from meal_contracts where store_id = ?", Long.class, storeId))
            .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select payment_type from meal_contracts where store_id = ?", String.class, storeId))
            .isEqualTo("POSTPAID");
        assertThat(jdbcTemplate.queryForObject("select prepaid_balance from meal_contracts where store_id = ?", Long.class, storeId))
            .isZero();
        assertThat(jdbcTemplate.queryForObject("select qr_selectable from meal_contracts where store_id = ?", Boolean.class, storeId))
            .isTrue();
        mockMvc.perform(get("/api/v1/store-meal-usage-qr").cookie(session.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("AVAILABLE"))
            .andExpect(jsonPath("$.publicPath").isString());
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from meal_usage_qr_contexts where store_id = ?", Long.class, storeId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from meal_usage_qr_operation_audits where store_id = ? and action = 'QR_ISSUED'",
            Long.class, storeId
        )).isEqualTo(1);
        mockMvc.perform(get("/api/v1/store-onboarding").cookie(session.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.onboardingStatus").value("COMPLETE"));
    }

    @Test
    void usesAuthenticatedStoreScopeForPrepaidPartnerAndNeverMergesSameNames() throws Exception {
        SessionHandle firstSession = StoreOnboardingHttpIntegrationSupport.signUpManualStore(
            mockMvc, objectMapper, "prepaid-store", "correct-password", "선불 가게"
        );
        SessionHandle secondSession = StoreOnboardingHttpIntegrationSupport.signUpManualStore(
            mockMvc, objectMapper, "second-store", "correct-password", "두 번째 가게"
        );

        mockMvc.perform(StoreOnboardingHttpIntegrationSupport.partnerRequest(
                firstSession,
                StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, firstSession),
                partnerBody("같은 이름 협력사", "PREPAID_WITH_RECEIVABLE_OVERFLOW", 120_000, false)
            ))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.paymentType").value("PREPAID_WITH_RECEIVABLE_OVERFLOW"));
        mockMvc.perform(StoreOnboardingHttpIntegrationSupport.partnerRequest(
                secondSession,
                StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, secondSession),
                partnerBody("같은 이름 협력사", "POSTPAID", 0, true)
            ))
            .andExpect(status().isCreated());

        UUID firstStoreId = storeIdFor("prepaid-store");
        UUID secondStoreId = storeIdFor("second-store");
        assertThat(firstStoreId).isNotEqualTo(secondStoreId);
        assertThat(jdbcTemplate.queryForObject("select prepaid_balance from meal_contracts where store_id = ?", Long.class, firstStoreId))
            .isEqualTo(120_000);
        assertThat(jdbcTemplate.queryForObject("select qr_selectable from meal_contracts where store_id = ?", Boolean.class, firstStoreId))
            .isFalse();
        assertThat(jdbcTemplate.queryForObject("select count(*) from meal_contracts where store_id = ?", Long.class, secondStoreId))
            .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from partner_organizations where display_name = ?", Long.class, "같은 이름 협력사"))
            .isEqualTo(2);
    }

    @Test
    void rejectsClientSuppliedStoreScopeBeforeCreatingTheFirstPartner() throws Exception {
        SessionHandle session = StoreOnboardingHttpIntegrationSupport.signUpManualStore(
            mockMvc, objectMapper, "scoped-store", "correct-password", "범위 검증 가게"
        );
        String requestWithStoreId = partnerBody("협력사 A", "POSTPAID", 0, true)
            .replace("}", ",\"storeId\":\"" + UUID.randomUUID() + "\"}");

        mockMvc.perform(StoreOnboardingHttpIntegrationSupport.partnerRequest(
                session,
                StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, session),
                requestWithStoreId
            ))
            .andExpect(StoreOnboardingHttpIntegrationSupport.problem(400, "VALIDATION_FAILED"));

        UUID storeId = storeIdFor("scoped-store");
        assertThat(jdbcTemplate.queryForObject("select count(*) from partner_organizations", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from meal_contracts where store_id = ?", Long.class, storeId))
            .isZero();
        assertThat(jdbcTemplate.queryForObject("select onboarding_status from stores where id = ?", String.class, storeId))
            .isEqualTo("PARTNER_REQUIRED");
    }

    @Test
    void rejectsInvalidExplicitContractInputAtomicallyAndKeepsPartnerRequired() throws Exception {
        SessionHandle session = StoreOnboardingHttpIntegrationSupport.signUpManualStore(
            mockMvc, objectMapper, "invalid-partner-store", "correct-password", "검증 가게"
        );
        String csrfToken = StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, session);

        mockMvc.perform(StoreOnboardingHttpIntegrationSupport.partnerRequest(
                session,
                csrfToken,
                partnerBody("협력사 A", "POSTPAID", 1, true)
            ))
            .andExpect(StoreOnboardingHttpIntegrationSupport.problem(400, "ONBOARDING_VALIDATION_FAILED"));
        mockMvc.perform(StoreOnboardingHttpIntegrationSupport.partnerRequest(
                session,
                csrfToken,
                "{\"partnerName\":\"협력사 A\",\"paymentType\":\"PREPAID_WITH_RECEIVABLE_OVERFLOW\",\"initialPrepaidBalanceMinor\":0}"
            ))
            .andExpect(StoreOnboardingHttpIntegrationSupport.problem(400, "ONBOARDING_VALIDATION_FAILED"));

        UUID storeId = storeIdFor("invalid-partner-store");
        assertThat(jdbcTemplate.queryForObject("select count(*) from partner_organizations", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from meal_contracts where store_id = ?", Long.class, storeId))
            .isZero();
        assertThat(jdbcTemplate.queryForObject("select onboarding_status from stores where id = ?", String.class, storeId))
            .isEqualTo("PARTNER_REQUIRED");
    }

    @Test
    void serializesConcurrentFirstPartnerRequestsAndLeavesExactlyOneContract() throws Exception {
        SessionHandle signUpSession = StoreOnboardingHttpIntegrationSupport.signUpManualStore(
            mockMvc, objectMapper, "concurrent-partner-store", "correct-password", "경합 협력사 가게"
        );
        SessionHandle secondSession = StoreOnboardingHttpIntegrationSupport.authenticatedSession(
            mockMvc, objectMapper, "concurrent-partner-store", "correct-password"
        );
        String firstCsrf = StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, signUpSession);
        String secondCsrf = StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, secondSession);
        String body = partnerBody("협력사 A", "POSTPAID", 0, true);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(partnerAttempt(start, signUpSession, firstCsrf, body));
            Future<Integer> second = executor.submit(partnerAttempt(start, secondSession, secondCsrf, body));
            start.countDown();

            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(201, 200);
        } finally {
            executor.shutdownNow();
        }

        UUID storeId = storeIdFor("concurrent-partner-store");
        assertThat(jdbcTemplate.queryForObject("select count(*) from partner_organizations", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from meal_contracts where store_id = ?", Long.class, storeId))
            .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select onboarding_status from stores where id = ?", String.class, storeId))
            .isEqualTo("COMPLETE");
    }

    @Test
    void treatsLegacyAccountAsAlreadyCompleteWithoutCreatingAPartner() throws Exception {
        UUID legacyStoreId = UUID.randomUUID();
        jdbcTemplate.update(
            "insert into store_accounts (login_id, password_hash, store_id, enabled) values (?, ?, ?, true)",
            "legacy-store", passwordEncoder.encode("correct-password"), legacyStoreId
        );
        SessionHandle session = StoreOnboardingHttpIntegrationSupport.authenticatedSession(
            mockMvc, objectMapper, "legacy-store", "correct-password"
        );

        mockMvc.perform(StoreOnboardingHttpIntegrationSupport.partnerRequest(
                session,
                StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, session),
                partnerBody("협력사 A", "POSTPAID", 0, true)
            ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.onboardingStatus").value("COMPLETE"))
            .andExpect(jsonPath("$.legacy").value(true))
            .andExpect(jsonPath("$.created").value(false));
        assertThat(jdbcTemplate.queryForObject("select count(*) from partner_organizations", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from meal_contracts", Long.class)).isZero();
    }

    private Callable<Integer> partnerAttempt(
        CountDownLatch start,
        SessionHandle session,
        String csrfToken,
        String body
    ) {
        return () -> {
            start.await(10, TimeUnit.SECONDS);
            return mockMvc.perform(StoreOnboardingHttpIntegrationSupport.partnerRequest(session, csrfToken, body))
                .andReturn()
                .getResponse()
                .getStatus();
        };
    }

    private UUID storeIdFor(String loginId) {
        return jdbcTemplate.queryForObject("select store_id from store_accounts where login_id = ?", UUID.class, loginId);
    }

    private String partnerBody(String partnerName, String paymentType, long initialPrepaidBalanceMinor, boolean qrSelectable) {
        return "{\"partnerName\":\"" + partnerName + "\",\"paymentType\":\"" + paymentType
            + "\",\"partnerKind\":\"ORGANIZATION\",\"initialPrepaidBalanceMinor\":" + initialPrepaidBalanceMinor
            + ",\"qrSelectable\":" + qrSelectable + "}";
    }
}
