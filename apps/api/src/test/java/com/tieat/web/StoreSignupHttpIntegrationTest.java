package com.tieat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class StoreSignupHttpIntegrationTest {

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
    }

    @BeforeEach
    void clearDatabase() {
        jdbcTemplate.execute("""
            truncate table stores, store_catalog_entries, store_accounts, partner_organizations, meal_contracts, meal_usages
            restart identity cascade
            """);
    }

    @Test
    void signsUpManualStoreAtomicallyRotatesSessionAndUsesItImmediately() throws Exception {
        MockHttpSession originalSession = StoreOnboardingHttpIntegrationSupport.csrfSession(mockMvc);
        String originalSessionId = originalSession.getId();

        MvcResult result = mockMvc.perform(StoreOnboardingHttpIntegrationSupport.signupRequest(
                originalSession,
                StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, originalSession),
                signupBody("new-store", "correct-password", null, "새로운 가게")
            ))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.onboardingStatus").value("PARTNER_REQUIRED"))
            .andReturn();

        MockHttpSession signedUpSession = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(signedUpSession.getId()).isNotEqualTo(originalSessionId);
        assertThat(result.getResponse().getContentAsString())
            .doesNotContain(StoreOnboardingHttpIntegrationSupport.INVITE_CODE)
            .doesNotContain("correct-password");

        UUID storeId = jdbcTemplate.queryForObject(
            "select store_id from store_accounts where login_id = ?", UUID.class, "new-store"
        );
        assertThat(jdbcTemplate.queryForObject("select count(*) from stores where id = ?", Long.class, storeId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select display_name from stores where id = ?", String.class, storeId))
            .isEqualTo("새로운 가게");
        assertThat(jdbcTemplate.queryForObject("select onboarding_status from stores where id = ?", String.class, storeId))
            .isEqualTo("PARTNER_REQUIRED");
        assertThat(jdbcTemplate.queryForObject("select password_hash from store_accounts where login_id = ?", String.class, "new-store"))
            .isNotEqualTo("correct-password");

        mockMvc.perform(get("/api/v1/store-onboarding").session(signedUpSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.onboardingStatus").value("PARTNER_REQUIRED"))
            .andExpect(jsonPath("$.legacy").value(false));
    }

    @Test
    void rejectsMissingOrWrongInviteCsrfAndInvalidNewAccountDataWithoutRows() throws Exception {
        mockMvc.perform(post("/api/v1/store-signups")
                .contentType(MediaType.APPLICATION_JSON)
                .content(signupBody("new-store", "correct-password", null, "새 가게")))
            .andExpect(StoreOnboardingHttpIntegrationSupport.problem(403, "CSRF_TOKEN_INVALID"));

        MockHttpSession session = StoreOnboardingHttpIntegrationSupport.csrfSession(mockMvc);
        String csrfToken = StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, session);
        mockMvc.perform(StoreOnboardingHttpIntegrationSupport.signupRequest(
                session,
                csrfToken,
                signupBodyWithInvite("wrong-code", "new-store", "correct-password", null, "새 가게")
            ))
            .andExpect(StoreOnboardingHttpIntegrationSupport.problem(403, "ONBOARDING_INVITE_INVALID"));
        mockMvc.perform(StoreOnboardingHttpIntegrationSupport.signupRequest(
                session,
                csrfToken,
                signupBody("UpperCase", "short", null, "새 가게")
            ))
            .andExpect(StoreOnboardingHttpIntegrationSupport.problem(400, "ONBOARDING_VALIDATION_FAILED"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from stores", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from store_accounts", Long.class)).isZero();
    }

    @Test
    void rejectsDuplicateAndConcurrentLoginIdsWithoutOrphanStores() throws Exception {
        StoreOnboardingHttpIntegrationSupport.signUpManualStore(
            mockMvc, objectMapper, "duplicate-store", "correct-password", "첫 가게"
        );
        MockHttpSession duplicateSession = StoreOnboardingHttpIntegrationSupport.csrfSession(mockMvc);
        mockMvc.perform(StoreOnboardingHttpIntegrationSupport.signupRequest(
                duplicateSession,
                StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, duplicateSession),
                signupBody("duplicate-store", "correct-password", null, "두 번째 가게")
            ))
            .andExpect(StoreOnboardingHttpIntegrationSupport.problem(409, "ONBOARDING_LOGIN_ID_IN_USE"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from stores", Long.class)).isEqualTo(1);

        MockHttpSession firstSession = StoreOnboardingHttpIntegrationSupport.csrfSession(mockMvc);
        MockHttpSession secondSession = StoreOnboardingHttpIntegrationSupport.csrfSession(mockMvc);
        String firstCsrf = StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, firstSession);
        String secondCsrf = StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, secondSession);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(signupAttempt(start, firstSession, firstCsrf, "경합 가게 A"));
            Future<Integer> second = executor.submit(signupAttempt(start, secondSession, secondCsrf, "경합 가게 B"));
            start.countDown();

            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(201, 409);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from store_accounts where login_id = ?", Long.class, "concurrent-store"))
            .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from stores", Long.class)).isEqualTo(2);
    }

    @Test
    void exposesOnlyPublicBoundedCatalogMetadataAndNeverUsesItAsALegacyStoreLink() throws Exception {
        UUID catalogId = UUID.randomUUID();
        UUID logoFreeCatalogId = UUID.randomUUID();
        UUID legacyStoreId = UUID.randomUUID();
        jdbcTemplate.update(
            "insert into store_catalog_entries (id, store_display_name, brand_display_name, logo_path) values (?, ?, ?, ?)",
            catalogId, "TIEAT 강남점", "TIEAT", "/logos/tieat.svg"
        );
        jdbcTemplate.update(
            "insert into store_catalog_entries (id, store_display_name, brand_display_name, logo_path) values (?, ?, ?, ?)",
            logoFreeCatalogId, "TIEAT 로고 없음", "TIEAT", "https://untrusted.example/logo.svg"
        );
        for (int index = 0; index < 10; index++) {
            jdbcTemplate.update(
                "insert into store_catalog_entries (id, store_display_name, brand_display_name, logo_path) values (?, ?, ?, null)",
                UUID.randomUUID(), "TIEAT 추가 " + index, "TIEAT"
            );
        }
        jdbcTemplate.update(
            "insert into store_accounts (login_id, password_hash, store_id, enabled) values (?, ?, ?, true)",
            "legacy-store", passwordEncoder.encode("correct-password"), legacyStoreId
        );

        mockMvc.perform(get("/api/v1/store-catalog").queryParam("query", "TIEAT"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(10))
            .andExpect(jsonPath("$.items[0].storeId").doesNotExist())
            .andExpect(jsonPath("$.items[0].account").doesNotExist());
        mockMvc.perform(get("/api/v1/store-catalog").queryParam("query", "강남"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].catalogEntryId").value(catalogId.toString()))
            .andExpect(jsonPath("$.items[0].storeDisplayName").value("TIEAT 강남점"))
            .andExpect(jsonPath("$.items[0].logoPath").value("/logos/tieat.svg"));
        mockMvc.perform(get("/api/v1/store-catalog").queryParam("query", "로고 없음"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].catalogEntryId").value(logoFreeCatalogId.toString()))
            .andExpect(jsonPath("$.items[0].logoPath").value(org.hamcrest.Matchers.nullValue()));

        MockHttpSession session = StoreOnboardingHttpIntegrationSupport.csrfSession(mockMvc);
        mockMvc.perform(StoreOnboardingHttpIntegrationSupport.signupRequest(
                session,
                StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, session),
                signupBody("catalog-store", "correct-password", catalogId, null)
            ))
            .andExpect(status().isCreated());
        UUID createdStoreId = jdbcTemplate.queryForObject(
            "select store_id from store_accounts where login_id = ?", UUID.class, "catalog-store"
        );
        assertThat(createdStoreId).isNotEqualTo(legacyStoreId);
        assertThat(jdbcTemplate.queryForObject("select catalog_entry_id from stores where id = ?", UUID.class, createdStoreId))
            .isEqualTo(catalogId);
    }

    @Test
    void preservesLegacyAccountsAsCompleteWithoutCreatingNewStoreRows() throws Exception {
        UUID legacyStoreId = UUID.randomUUID();
        jdbcTemplate.update(
            "insert into store_accounts (login_id, password_hash, store_id, enabled) values (?, ?, ?, true)",
            "legacy-store", passwordEncoder.encode("correct-password"), legacyStoreId
        );

        MockHttpSession session = StoreOnboardingHttpIntegrationSupport.authenticatedSession(
            mockMvc, objectMapper, "legacy-store", "correct-password"
        );
        mockMvc.perform(get("/api/v1/store-onboarding").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.onboardingStatus").value("COMPLETE"))
            .andExpect(jsonPath("$.legacy").value(true));
        assertThat(jdbcTemplate.queryForObject("select count(*) from stores", Long.class)).isZero();
    }

    private Callable<Integer> signupAttempt(
        CountDownLatch start,
        MockHttpSession session,
        String csrfToken,
        String storeName
    ) {
        return () -> {
            start.await(10, TimeUnit.SECONDS);
            return mockMvc.perform(StoreOnboardingHttpIntegrationSupport.signupRequest(
                    session,
                    csrfToken,
                    signupBody("concurrent-store", "correct-password", null, storeName)
                ))
                .andReturn()
                .getResponse()
                .getStatus();
        };
    }

    private String signupBody(String loginId, String password, UUID catalogEntryId, String manualStoreName) {
        return signupBodyWithInvite(
            StoreOnboardingHttpIntegrationSupport.INVITE_CODE,
            loginId,
            password,
            catalogEntryId,
            manualStoreName
        );
    }

    private String signupBodyWithInvite(
        String inviteCode,
        String loginId,
        String password,
        UUID catalogEntryId,
        String manualStoreName
    ) {
        String catalogField = catalogEntryId == null ? "" : ",\"catalogEntryId\":\"" + catalogEntryId + "\"";
        String manualField = manualStoreName == null ? "" : ",\"manualStoreName\":\"" + manualStoreName + "\"";
        return "{\"inviteCode\":\"" + inviteCode + "\",\"loginId\":\"" + loginId + "\",\"password\":\""
            + password + "\"" + catalogField + manualField + "}";
    }
}
