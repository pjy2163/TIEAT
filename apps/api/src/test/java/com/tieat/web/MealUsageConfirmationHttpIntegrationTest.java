package com.tieat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.doAnswer;

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
import com.tieat.web.StoreOnboardingHttpIntegrationSupport.SessionHandle;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
class MealUsageConfirmationHttpIntegrationTest {

    private static final StoreId STORE_ID = new StoreId(UUID.fromString("9d5e37dd-dbe2-40dc-97fb-8e77c89aa4cb"));
    private static final StoreId OTHER_STORE_ID = new StoreId(UUID.fromString("6142be7d-0dc9-4f77-a17d-07e1e5c6e9a1"));

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
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

    @MockitoSpyBean
    private MealUsageRepository mealUsageRepository;

    @Autowired
    private MealContractRepository mealContractRepository;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void clearDatabase() {
        jdbcTemplate.update("delete from auth_abuse_rate_limits");
        jdbcTemplate.update("delete from meal_usages");
        jdbcTemplate.update("delete from meal_contracts");
        jdbcTemplate.update("delete from store_accounts");
    }

    @Test
    void returnsCsrfTokenForAnonymousSession() throws Exception {
        mockMvc.perform(get("/api/v1/csrf"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
            .andExpect(jsonPath("$.parameterName").value("_csrf"));
    }

    @Test
    void logsInWithBcryptAccountThenConfirmsUsageAgainstPostgresBalance() throws Exception {
        seedAccount("store-hk", "correct-password", STORE_ID, true);
        MealContract contract = prepaidContract(STORE_ID, 10_000);
        MealUsage usage = pendingUsage(contract.id(), STORE_ID, 12_000);
        mealContractRepository.save(contract);
        mealUsageRepository.save(usage);

        SessionHandle session = csrfSession();
        String csrfToken = csrfToken(session);
        MvcResult login = mockMvc.perform(post("/api/v1/sessions")
                .cookie(session.cookie())
                .header("X-CSRF-TOKEN", csrfToken)
                .param("loginId", "store-hk")
                .param("password", "correct-password"))
            .andExpect(status().isNoContent())
            .andReturn();
        SessionHandle authenticatedSession = authenticatedSession(login);
        String confirmationCsrfToken = csrfToken(authenticatedSession);

        mockMvc.perform(post("/api/v1/meal-usages/{mealUsageId}/confirmations", usage.id().value())
                .cookie(authenticatedSession.cookie())
                .header("X-CSRF-TOKEN", confirmationCsrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmerInitials\":\"HK\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.mealUsageId").value(usage.id().value().toString()))
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.confirmerInitials").value("HK"))
            .andExpect(jsonPath("$.amountMinor").value(12_000))
            .andExpect(jsonPath("$.prepaidAppliedMinor").value(10_000))
            .andExpect(jsonPath("$.receivableCreatedMinor").value(2_000))
            .andExpect(jsonPath("$.prepaidRemainingMinor").value(0));
        assertThat(mealUsageRepository.findById(usage.id()).orElseThrow().status()).isEqualTo(MealUsageStatus.CONFIRMED);
        assertThat(jdbcTemplate.queryForObject("select prepaid_balance from meal_contracts", Long.class)).isZero();
        mockMvc.perform(confirmRequest(
            authenticatedSession,
            confirmationCsrfToken,
            usage.id().value(),
            "{\"confirmerInitials\":\"JS\"}"
        )).andExpect(problem(HttpStatus.CONFLICT.value(), "MEAL_USAGE_ALREADY_CONFIRMED"));
        assertThat(jdbcTemplate.queryForObject("select prepaid_balance from meal_contracts", Long.class)).isZero();
    }

    @Test
    void confirmsSamePendingUsageConcurrentlyExactlyOnceAgainstPostgres() throws Exception {
        seedAccount("store-hk", "correct-password", STORE_ID, true);
        MealContract contract = prepaidContract(STORE_ID, 10_000);
        MealUsage usage = pendingUsage(contract.id(), STORE_ID, 12_000);
        mealContractRepository.save(contract);
        mealUsageRepository.save(usage);

        SessionHandle firstSession = authenticatedSession("store-hk", "correct-password");
        SessionHandle secondSession = authenticatedSession("store-hk", "correct-password");
        String firstCsrfToken = csrfToken(firstSession);
        String secondCsrfToken = csrfToken(secondSession);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch firstLockAcquired = new CountDownLatch(1);
        CountDownLatch secondLookupStarted = new CountDownLatch(1);
        CountDownLatch secondLookupCompleted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean firstLookup = new AtomicBoolean(true);
        doAnswer(invocation -> {
            boolean holdLock = firstLookup.compareAndSet(true, false);
            if (!holdLock) {
                await(firstLockAcquired);
                secondLookupStarted.countDown();
            }
            Object result = invocation.callRealMethod();
            if (holdLock) {
                firstLockAcquired.countDown();
                await(releaseFirst);
            } else {
                secondLookupCompleted.countDown();
            }
            return result;
        }).when(mealUsageRepository).findByIdForUpdate(usage.id());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> firstConfirmation = executor.submit(() -> confirmAtStart(
                ready, start, firstSession, firstCsrfToken, usage.id().value(), "HK"
            ));
            Future<MvcResult> secondConfirmation = executor.submit(() -> confirmAtStart(
                ready, start, secondSession, secondCsrfToken, usage.id().value(), "JS"
            ));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(firstLockAcquired.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(secondLookupStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(secondLookupCompleted.await(1, TimeUnit.SECONDS)).isFalse();
            releaseFirst.countDown();

            List<MvcResult> confirmations = List.of(
                firstConfirmation.get(10, TimeUnit.SECONDS),
                secondConfirmation.get(10, TimeUnit.SECONDS)
            );
            assertThat(confirmations).extracting(result -> result.getResponse().getStatus()).containsExactlyInAnyOrder(
                HttpStatus.CREATED.value(),
                HttpStatus.CONFLICT.value()
            );
            MvcResult conflict = confirmations.stream()
                .filter(result -> result.getResponse().getStatus() == HttpStatus.CONFLICT.value())
                .findFirst()
                .orElseThrow();
            assertThat(conflict.getResponse().getContentAsString())
                .contains("\"errorCode\":\"MEAL_USAGE_ALREADY_CONFIRMED\"");
        } finally {
            releaseFirst.countDown();
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from meal_usages where id = ? and status = 'CONFIRMED'",
            Long.class,
            usage.id().value()
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "select prepaid_balance from meal_contracts where id = ?",
            Long.class,
            contract.id().value()
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from meal_usages where meal_contract_id = ? and prepaid_applied is not null",
            Long.class,
            contract.id().value()
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "select sum(prepaid_applied) from meal_usages where meal_contract_id = ?",
            Long.class,
            contract.id().value()
        )).isEqualTo(10_000);
        assertThat(jdbcTemplate.queryForObject(
            "select sum(receivable_created) from meal_usages where meal_contract_id = ?",
            Long.class,
            contract.id().value()
        )).isEqualTo(2_000);
        assertThat(jdbcTemplate.queryForObject(
            "select sum(remaining_prepaid) from meal_usages where meal_contract_id = ?",
            Long.class,
            contract.id().value()
        )).isZero();
    }

    @Test
    void rejectsBadPasswordAndDisabledAccountWithoutLeakingAccountDetails() throws Exception {
        seedAccount("store-hk", "correct-password", STORE_ID, true);
        SessionHandle badPasswordSession = csrfSession();
        mockMvc.perform(loginRequest(badPasswordSession, "store-hk", "wrong-password", csrfToken(badPasswordSession)))
            .andExpect(problem(HttpStatus.UNAUTHORIZED.value(), "AUTHENTICATION_FAILED"));

        jdbcTemplate.update("delete from auth_abuse_rate_limits");
        jdbcTemplate.update("update store_accounts set enabled = false where login_id = ?", "store-hk");
        SessionHandle disabledSession = csrfSession();
        mockMvc.perform(loginRequest(disabledSession, "store-hk", "correct-password", csrfToken(disabledSession)))
            .andExpect(problem(HttpStatus.UNAUTHORIZED.value(), "AUTHENTICATION_FAILED"));
    }

    @Test
    void rejectsUnauthenticatedAndInvalidCsrfRequests() throws Exception {
        UUID usageId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/meal-usages/{mealUsageId}/confirmations", usageId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmerInitials\":\"HK\"}"))
            .andExpect(problem(HttpStatus.UNAUTHORIZED.value(), "AUTHENTICATION_REQUIRED"));

        mockMvc.perform(post("/api/v1/meal-usages/{mealUsageId}/confirmations", usageId)
                .with(user("store-staff").roles("STORE_STAFF"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmerInitials\":\"HK\"}"))
            .andExpect(problem(HttpStatus.FORBIDDEN.value(), "CSRF_TOKEN_INVALID"));

        mockMvc.perform(post("/api/v1/meal-usages/{mealUsageId}/confirmations", usageId)
                .with(user("wrong-role").roles("PARTNER"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmerInitials\":\"HK\"}"))
            .andExpect(problem(HttpStatus.FORBIDDEN.value(), "ACCESS_DENIED"));
    }

    @Test
    void rejectsInvalidBodiesAndHidesMissingOrCrossStoreUsage() throws Exception {
        seedAccount("store-hk", "correct-password", STORE_ID, true);
        MealContract crossStoreContract = prepaidContract(OTHER_STORE_ID, 8_000);
        MealUsage crossStoreUsage = pendingUsage(crossStoreContract.id(), OTHER_STORE_ID, 8_000);
        mealContractRepository.save(crossStoreContract);
        mealUsageRepository.save(crossStoreUsage);
        SessionHandle session = authenticatedSession("store-hk", "correct-password");
        String csrfToken = csrfToken(session);

        mockMvc.perform(confirmRequest(session, csrfToken, crossStoreUsage.id().value(), "{\"confirmerInitials\":\"\"}"))
            .andExpect(problem(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED"));
        mockMvc.perform(confirmRequest(session, csrfToken, crossStoreUsage.id().value(), "{\"confirmerInitials\":\"HK\",\"storeId\":\"x\"}"))
            .andExpect(problem(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED"));
        mockMvc.perform(confirmRequest(session, csrfToken, crossStoreUsage.id().value(), "{}"))
            .andExpect(problem(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED"));
        mockMvc.perform(post("/api/v1/meal-usages/not-a-uuid/confirmations")
                .cookie(session.cookie())
                .header("X-CSRF-TOKEN", csrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmerInitials\":\"HK\"}"))
            .andExpect(problem(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED"));
        mockMvc.perform(confirmRequest(session, csrfToken, UUID.randomUUID(), "{\"confirmerInitials\":\"HK\"}"))
            .andExpect(problem(HttpStatus.NOT_FOUND.value(), "MEAL_USAGE_NOT_FOUND"));
        mockMvc.perform(confirmRequest(session, csrfToken, crossStoreUsage.id().value(), "{\"confirmerInitials\":\"HK\"}"))
            .andExpect(problem(HttpStatus.NOT_FOUND.value(), "MEAL_USAGE_NOT_FOUND"));
        assertThat(mealUsageRepository.findById(crossStoreUsage.id()).orElseThrow().status()).isEqualTo(MealUsageStatus.PENDING);
    }

    @Test
    void exposesOnlyApiRoutesAndProblemContractsInOpenApiDocument() throws Exception {
        mockMvc.perform(get("/v3/api-docs")
                .with(user("openapi-contract")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/v1/meal-usages/{mealUsageId}/confirmations'].post").exists())
            .andExpect(jsonPath("$.components.schemas.ConfirmationRequest.properties.confirmerInitials").exists())
            .andExpect(jsonPath("$.components.schemas.ConfirmationRequest.properties.length()").value(1))
            .andExpect(jsonPath("$.paths['/api/v1/meal-usages/{mealUsageId}/confirmations'].post.responses['201']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/meal-usages/{mealUsageId}/confirmations'].post.responses['500']").exists())
            .andExpect(jsonPath("$.components.schemas.ProblemResponse.properties.errorCode").exists());
    }

    private SessionHandle authenticatedSession(String loginId, String password) throws Exception {
        SessionHandle session = csrfSession();
        MvcResult login = mockMvc.perform(loginRequest(session, loginId, password, csrfToken(session)))
            .andExpect(status().isNoContent())
            .andReturn();
        return authenticatedSession(login);
    }

    private SessionHandle authenticatedSession(MvcResult result) throws Exception {
        return StoreOnboardingHttpIntegrationSupport.authenticatedSession(mockMvc, objectMapper, result);
    }

    private SessionHandle csrfSession() throws Exception {
        return StoreOnboardingHttpIntegrationSupport.csrfSession(mockMvc, objectMapper);
    }

    private String csrfToken(SessionHandle session) throws Exception {
        return StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, session);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder loginRequest(
        SessionHandle session,
        String loginId,
        String password,
        String csrfToken
    ) {
        return post("/api/v1/sessions")
            .cookie(session.cookie())
            .header("X-CSRF-TOKEN", csrfToken)
            .param("loginId", loginId)
            .param("password", password);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder confirmRequest(
        SessionHandle session,
        String csrfToken,
        UUID mealUsageId,
        String body
    ) {
        return post("/api/v1/meal-usages/{mealUsageId}/confirmations", mealUsageId)
            .cookie(session.cookie())
            .header("X-CSRF-TOKEN", csrfToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    }

    private MvcResult confirmAtStart(
        CountDownLatch ready,
        CountDownLatch start,
        SessionHandle session,
        String csrfToken,
        UUID mealUsageId,
        String confirmerInitials
    ) throws Exception {
        ready.countDown();
        await(start);
        return mockMvc.perform(confirmRequest(
            session,
            csrfToken,
            mealUsageId,
            "{\"confirmerInitials\":\"" + confirmerInitials + "\"}"
        )).andReturn();
    }

    private void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for concurrent confirmation", exception);
        }
    }

    private org.springframework.test.web.servlet.ResultMatcher problem(int expectedStatus, String errorCode) {
        return result -> {
            status().is(expectedStatus).match(result);
            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON).match(result);
            jsonPath("$.type").exists().match(result);
            jsonPath("$.title").exists().match(result);
            jsonPath("$.status").value(expectedStatus).match(result);
            jsonPath("$.detail").exists().match(result);
            jsonPath("$.instance").exists().match(result);
            jsonPath("$.errorCode").value(errorCode).match(result);
        };
    }

    private void seedAccount(String loginId, String password, StoreId storeId, boolean enabled) {
        jdbcTemplate.update(
            "insert into store_accounts (login_id, password_hash, store_id, enabled) values (?, ?, ?, ?)",
            loginId,
            passwordEncoder.encode(password),
            storeId.value(),
            enabled
        );
    }

    private MealUsage pendingUsage(MealContractId mealContractId, StoreId storeId, long amount) {
        return MealUsage.pending(
            new MealUsageId(UUID.randomUUID()),
            storeId,
            mealContractId,
            EntrySource.STORE_TABLET,
            amount,
            Instant.parse("2026-08-06T01:00:00Z")
        );
    }

    private MealContract prepaidContract(StoreId storeId, long prepaidBalance) {
        return new MealContract(
            new MealContractId(UUID.randomUUID()),
            storeId,
            MealContractPaymentType.PREPAID_WITH_RECEIVABLE_OVERFLOW,
            prepaidBalance
        );
    }
}
