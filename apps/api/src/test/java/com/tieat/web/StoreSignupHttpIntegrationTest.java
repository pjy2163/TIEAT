package com.tieat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tieat.onboarding.application.OnboardingException;
import com.tieat.onboarding.application.StorePlaceSearchGateway;
import com.tieat.onboarding.application.StorePlaceSearchGateway.PlaceSearchResult;
import com.tieat.web.StoreOnboardingHttpIntegrationSupport.SessionHandle;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(StoreSignupHttpIntegrationTest.PlaceSearchTestConfiguration.class)
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

    @Autowired
    private StubPlaceSearchGateway placeSearchGateway;

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
            truncate table store_partner_registrations, stores, store_catalog_entries, store_accounts, partner_organizations, meal_contracts, meal_usages, auth_abuse_rate_limits
            restart identity cascade
            """);
        placeSearchGateway.reset();
    }

    @Test
    void signsUpManualStoreAtomicallyRotatesSessionAndUsesItImmediately() throws Exception {
        SessionHandle originalSession = StoreOnboardingHttpIntegrationSupport.csrfSession(mockMvc, objectMapper);
        String originalSessionId = originalSession.sessionId();

        MvcResult result = mockMvc.perform(StoreOnboardingHttpIntegrationSupport.signupRequest(
                originalSession,
                StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, originalSession),
                signupBody("new-store", "correct-password", "새로운 가게")
            ))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.onboardingStatus").value("PARTNER_REQUIRED"))
            .andReturn();

        SessionHandle signedUpSession = StoreOnboardingHttpIntegrationSupport.authenticatedSession(mockMvc, objectMapper, result);
        assertThat(signedUpSession.sessionId()).isNotEqualTo(originalSessionId);
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
        assertThat(jdbcTemplate.queryForObject("select catalog_entry_id is null from stores where id = ?", Boolean.class, storeId))
            .isTrue();
        assertThat(jdbcTemplate.queryForObject("select password_hash from store_accounts where login_id = ?", String.class, "new-store"))
            .isNotEqualTo("correct-password");

        mockMvc.perform(get("/api/v1/store-onboarding").cookie(signedUpSession.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.onboardingStatus").value("PARTNER_REQUIRED"))
            .andExpect(jsonPath("$.legacy").value(false));
    }

    @Test
    void rejectsMissingOrWrongInviteCsrfAndInvalidNewAccountDataWithoutRows() throws Exception {
        mockMvc.perform(post("/api/v1/store-signups")
                .contentType(MediaType.APPLICATION_JSON)
                .content(signupBody("new-store", "correct-password", "새 가게")))
            .andExpect(StoreOnboardingHttpIntegrationSupport.problem(403, "CSRF_TOKEN_INVALID"));

        SessionHandle session = StoreOnboardingHttpIntegrationSupport.csrfSession(mockMvc, objectMapper);
        String csrfToken = StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, session);
        mockMvc.perform(StoreOnboardingHttpIntegrationSupport.signupRequest(
                session,
                csrfToken,
                signupBodyWithInvite("wrong-code", "new-store", "correct-password", "새 가게")
            ))
            .andExpect(StoreOnboardingHttpIntegrationSupport.problem(403, "ONBOARDING_INVITE_INVALID"));
        jdbcTemplate.update("delete from auth_abuse_rate_limits");
        mockMvc.perform(StoreOnboardingHttpIntegrationSupport.signupRequest(
                session,
                csrfToken,
                signupBody("UpperCase", "short", "새 가게")
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
        SessionHandle duplicateSession = StoreOnboardingHttpIntegrationSupport.csrfSession(mockMvc, objectMapper);
        mockMvc.perform(StoreOnboardingHttpIntegrationSupport.signupRequest(
                duplicateSession,
                StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, duplicateSession),
                signupBody("duplicate-store", "correct-password", "두 번째 가게")
            ))
            .andExpect(StoreOnboardingHttpIntegrationSupport.problem(409, "ONBOARDING_LOGIN_ID_IN_USE"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from stores", Long.class)).isEqualTo(1);

        SessionHandle firstSession = StoreOnboardingHttpIntegrationSupport.csrfSession(mockMvc, objectMapper);
        SessionHandle secondSession = StoreOnboardingHttpIntegrationSupport.csrfSession(mockMvc, objectMapper);
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
    void searchesOnlyForAValidInviteProjectsNaverDisplayDataAndKeepsItOutOfStorePersistence() throws Exception {
        SessionHandle session = StoreOnboardingHttpIntegrationSupport.csrfSession(mockMvc, objectMapper);
        String csrfToken = StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, session);

        mockMvc.perform(placeSearchRequest(session, csrfToken, "wrong-code", "TIEAT"))
            .andExpect(StoreOnboardingHttpIntegrationSupport.problem(403, "ONBOARDING_INVITE_INVALID"));
        jdbcTemplate.update("delete from auth_abuse_rate_limits");
        mockMvc.perform(placeSearchRequest(session, csrfToken, StoreOnboardingHttpIntegrationSupport.INVITE_CODE, "X"))
            .andExpect(StoreOnboardingHttpIntegrationSupport.problem(400, "STORE_PLACE_SEARCH_INVALID"));
        assertThat(placeSearchGateway.calls()).isZero();

        placeSearchGateway.returnResults(List.of(
            new PlaceSearchResult("26338954", "TIEAT 강남점", "서울 강남구 테헤란로 123", "음식점 > 한식"),
            new PlaceSearchResult("26338955", "TIEAT 역삼점", null, "음식점 > 분식")
        ));
        mockMvc.perform(placeSearchRequest(session, csrfToken, StoreOnboardingHttpIntegrationSupport.INVITE_CODE, "TIEAT"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.source").value("NAVER"))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].placeId").value("26338954"))
            .andExpect(jsonPath("$.items[0].storeDisplayName").value("TIEAT 강남점"))
            .andExpect(jsonPath("$.items[0].address").value("서울 강남구 테헤란로 123"))
            .andExpect(jsonPath("$.items[0].category").value("음식점 > 한식"))
            .andExpect(jsonPath("$.items[0].phone").doesNotExist())
            .andExpect(jsonPath("$.items[0].storeId").doesNotExist());
        assertThat(placeSearchGateway.calls()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from stores", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from store_catalog_entries", Long.class)).isZero();

        placeSearchGateway.makeUnavailable();
        mockMvc.perform(placeSearchRequest(session, csrfToken, StoreOnboardingHttpIntegrationSupport.INVITE_CODE, "TIEAT"))
            .andExpect(StoreOnboardingHttpIntegrationSupport.problem(503, "STORE_PLACE_SEARCH_UNAVAILABLE"));
    }

    @Test
    void rateLimitsRepeatedInvalidPlaceInvitesWithoutPersistingInviteOrIp() throws Exception {
        SessionHandle session = StoreOnboardingHttpIntegrationSupport.csrfSession(mockMvc, objectMapper);
        String csrfToken = StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, session);

        mockMvc.perform(placeSearchRequest(session, csrfToken, "wrong-code", "TIEAT"))
            .andExpect(StoreOnboardingHttpIntegrationSupport.problem(403, "ONBOARDING_INVITE_INVALID"));
        mockMvc.perform(placeSearchRequest(session, csrfToken, "wrong-code", "TIEAT"))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string(HttpHeaders.RETRY_AFTER, org.hamcrest.Matchers.notNullValue()))
            .andExpect(jsonPath("$.errorCode").value("REQUEST_RATE_LIMITED"))
            .andExpect(jsonPath("$.detail").value("Too many requests"))
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("wrong-code"))));
        List<String> keyHashes = jdbcTemplate.query(
            "select key_hash from auth_abuse_rate_limits",
            (resultSet, rowNum) -> resultSet.getString("key_hash")
        );
        assertThat(keyHashes).isNotEmpty().allMatch(hash -> hash.matches("[0-9a-f]{64}"));

        jdbcTemplate.update(
            "update auth_abuse_rate_limits set window_started_at = now() - interval '16 minutes', "
                + "available_at = now() - interval '1 second', blocked_until = now() - interval '1 second'"
        );
        placeSearchGateway.returnResults(List.of(new PlaceSearchResult("1", "TIEAT", "주소", "음식점")));
        mockMvc.perform(placeSearchRequest(session, csrfToken, StoreOnboardingHttpIntegrationSupport.INVITE_CODE, "TIEAT"))
            .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("select count(*) from auth_abuse_rate_limits", Long.class)).isZero();
    }

    @Test
    void rateLimitsLoginByIpAndIdentityAndRecoversAfterWindow() throws Exception {
        String loginId = "rate-limit-store";
        String password = "correct-password";
        jdbcTemplate.update(
            "insert into store_accounts (login_id, password_hash, store_id, enabled) values (?, ?, ?, true)",
            loginId, passwordEncoder.encode(password), UUID.randomUUID()
        );
        SessionHandle session = StoreOnboardingHttpIntegrationSupport.csrfSession(mockMvc, objectMapper);
        String csrfToken = StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, session);
        for (int attempt = 1; attempt <= 5; attempt++) {
            mockMvc.perform(post("/api/v1/sessions").cookie(session.cookie()).header("X-CSRF-TOKEN", csrfToken)
                    .with(request -> {
                        request.setRemoteAddr("198.51.100.10");
                        return request;
                    })
                    .param("loginId", loginId).param("password", "wrong-password"))
                .andExpect(status().is(attempt == 5 ? 429 : 401));
            jdbcTemplate.update("update auth_abuse_rate_limits set available_at = now() - interval '1 second'");
        }
        mockMvc.perform(post("/api/v1/sessions").cookie(session.cookie()).header("X-CSRF-TOKEN", csrfToken)
                .with(request -> {
                    request.setRemoteAddr("203.0.113.10");
                    return request;
                })
                .param("loginId", loginId).param("password", password))
            .andExpect(status().isTooManyRequests());
        mockMvc.perform(post("/api/v1/sessions").cookie(session.cookie()).header("X-CSRF-TOKEN", csrfToken)
                .with(request -> {
                    request.setRemoteAddr("198.51.100.10");
                    return request;
                })
                .param("loginId", "another-login-id").param("password", "wrong-password"))
            .andExpect(status().isTooManyRequests());

        jdbcTemplate.update(
            "update auth_abuse_rate_limits set window_started_at = now() - interval '16 minutes', "
                + "available_at = now() - interval '1 second', blocked_until = now() - interval '1 second'"
        );
        mockMvc.perform(post("/api/v1/sessions").cookie(session.cookie()).header("X-CSRF-TOKEN", csrfToken)
                .with(request -> {
                    request.setRemoteAddr("198.51.100.10");
                    return request;
                })
                .param("loginId", loginId).param("password", password))
            .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("select count(*) from auth_abuse_rate_limits", Long.class)).isZero();
    }

    @Test
    void rateLimitsRepeatedInvalidSignupInvitesBeforeCreatingRows() throws Exception {
        SessionHandle session = StoreOnboardingHttpIntegrationSupport.csrfSession(mockMvc, objectMapper);
        String csrfToken = StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, session);

        mockMvc.perform(StoreOnboardingHttpIntegrationSupport.signupRequest(
                session, csrfToken, signupBodyWithInvite("wrong-code", "rate-limit-signup", "correct-password", "가게")
            ))
            .andExpect(StoreOnboardingHttpIntegrationSupport.problem(403, "ONBOARDING_INVITE_INVALID"));
        mockMvc.perform(StoreOnboardingHttpIntegrationSupport.signupRequest(
                session, csrfToken, signupBodyWithInvite("wrong-code", "rate-limit-signup", "correct-password", "가게")
            ))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.errorCode").value("REQUEST_RATE_LIMITED"))
            .andExpect(jsonPath("$.detail").value("Too many requests"));

        assertThat(jdbcTemplate.queryForObject("select count(*) from stores", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from store_accounts", Long.class)).isZero();
    }

    @Test
    void preservesLegacyAccountsAsCompleteWithoutCreatingNewStoreRows() throws Exception {
        UUID legacyStoreId = UUID.randomUUID();
        jdbcTemplate.update(
            "insert into store_accounts (login_id, password_hash, store_id, enabled) values (?, ?, ?, true)",
            "legacy-store", passwordEncoder.encode("correct-password"), legacyStoreId
        );

        SessionHandle session = StoreOnboardingHttpIntegrationSupport.authenticatedSession(
            mockMvc, objectMapper, "legacy-store", "correct-password"
        );
        mockMvc.perform(get("/api/v1/store-onboarding").cookie(session.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.onboardingStatus").value("COMPLETE"))
            .andExpect(jsonPath("$.legacy").value(true));
        assertThat(jdbcTemplate.queryForObject("select count(*) from stores", Long.class)).isZero();
    }

    private Callable<Integer> signupAttempt(
        CountDownLatch start,
        SessionHandle session,
        String csrfToken,
        String storeName
    ) {
        return () -> {
            start.await(10, TimeUnit.SECONDS);
            return mockMvc.perform(StoreOnboardingHttpIntegrationSupport.signupRequest(
                    session,
                    csrfToken,
                    signupBody("concurrent-store", "correct-password", storeName)
                ))
                .andReturn()
                .getResponse()
                .getStatus();
        };
    }

    private MockHttpServletRequestBuilder placeSearchRequest(
        SessionHandle session,
        String csrfToken,
        String inviteCode,
        String query
    ) {
        return post("/api/v1/store-place-searches")
            .cookie(session.cookie())
            .header("X-CSRF-TOKEN", csrfToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"inviteCode\":\"" + inviteCode + "\",\"query\":\"" + query + "\"}");
    }

    private String signupBody(String loginId, String password, String manualStoreName) {
        return signupBodyWithInvite(StoreOnboardingHttpIntegrationSupport.INVITE_CODE, loginId, password, manualStoreName);
    }

    private String signupBodyWithInvite(String inviteCode, String loginId, String password, String manualStoreName) {
        return "{\"inviteCode\":\"" + inviteCode + "\",\"loginId\":\"" + loginId + "\",\"password\":\""
            + password + "\",\"manualStoreName\":\"" + manualStoreName + "\"}";
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PlaceSearchTestConfiguration {

        @Bean
        @Primary
        StubPlaceSearchGateway storePlaceSearchGateway() {
            return new StubPlaceSearchGateway();
        }
    }

    static final class StubPlaceSearchGateway implements StorePlaceSearchGateway {

        private final AtomicInteger calls = new AtomicInteger();
        private List<PlaceSearchResult> results = List.of();
        private boolean unavailable;

        @Override
        public List<PlaceSearchResult> search(String query) {
            calls.incrementAndGet();
            if (unavailable) {
                throw OnboardingException.placeSearchUnavailable();
            }
            return results;
        }

        void returnResults(List<PlaceSearchResult> results) {
            this.results = List.copyOf(results);
        }

        void makeUnavailable() {
            unavailable = true;
        }

        int calls() {
            return calls.get();
        }

        void reset() {
            calls.set(0);
            results = List.of();
            unavailable = false;
        }
    }
}
