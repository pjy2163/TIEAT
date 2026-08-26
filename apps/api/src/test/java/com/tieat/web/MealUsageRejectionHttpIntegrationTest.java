package com.tieat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import java.util.Set;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MealUsageRejectionHttpIntegrationTest {

    private static final StoreId STORE_ID = new StoreId(UUID.fromString("9d5e37dd-dbe2-40dc-97fb-8e77c89aa4cb"));
    private static final StoreId OTHER_STORE_ID = new StoreId(UUID.fromString("6142be7d-0dc9-4f77-a17d-07e1e5c6e9a1"));

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
        jdbcTemplate.update("delete from public_meal_usage_idempotency_keys");
        jdbcTemplate.update("delete from meal_usages");
        jdbcTemplate.update("delete from meal_contracts");
        jdbcTemplate.update("delete from store_accounts");
    }

    @Test
    void authenticatedStoreStaffRejectsPendingUsageWithAuditWithoutAllocation() throws Exception {
        seedAccount("store-hk", "correct-password", STORE_ID);
        MealContract contract = contract(STORE_ID, 10_000);
        MealUsage usage = pendingUsage(STORE_ID, contract.id());
        mealContractRepository.save(contract);
        mealUsageRepository.save(usage);
        SessionHandle session = authenticatedSession("store-hk", "correct-password");

        MvcResult result = mockMvc.perform(post("/api/v1/meal-usages/{mealUsageId}/rejections", usage.id().value())
                .cookie(session.cookie())
                .header("X-CSRF-TOKEN", csrfToken(session)))
            .andExpect(status().isCreated())
            .andExpect(header().string(HttpHeaders.LOCATION, "http://localhost/api/v1/meal-usages/" + usage.id().value() + "/rejections"))
            .andExpect(jsonPath("$.mealUsageId").value(usage.id().value().toString()))
            .andExpect(jsonPath("$.status").value("REJECTED"))
            .andExpect(jsonPath("$.rejectedAt").isNotEmpty())
            .andExpect(jsonPath("$.version").value(1))
            .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(Set.copyOf(response.propertyNames())).containsExactlyInAnyOrder("mealUsageId", "status", "rejectedAt", "version");
        assertThat(mealUsageRepository.findById(usage.id())).hasValueSatisfying(rejected -> {
            assertThat(rejected.status()).isEqualTo(MealUsageStatus.REJECTED);
            assertThat(rejected.rejection()).hasValueSatisfying(audit -> assertThat(audit.staffLoginId()).isEqualTo("store-hk"));
            assertThat(rejected.confirmation()).isEmpty();
            assertThat(rejected.prepaidAllocation()).isEmpty();
        });
        assertThat(jdbcTemplate.queryForObject(
            "select prepaid_balance from meal_contracts where id = ?", Long.class, contract.id().value()
        )).isEqualTo(10_000);
    }

    @Test
    void protectsRejectionBySessionCsrfAndStoreScopeAndRejectsSecondTerminalTransition() throws Exception {
        seedAccount("store-hk", "correct-password", STORE_ID);
        seedAccount("other-store", "correct-password", OTHER_STORE_ID);
        MealContract contract = contract(STORE_ID, 10_000);
        MealUsage usage = pendingUsage(STORE_ID, contract.id());
        mealContractRepository.save(contract);
        mealUsageRepository.save(usage);
        SessionHandle session = authenticatedSession("store-hk", "correct-password");
        SessionHandle otherStoreSession = authenticatedSession("other-store", "correct-password");

        mockMvc.perform(post("/api/v1/meal-usages/{mealUsageId}/rejections", usage.id().value()))
            .andExpect(problem(HttpStatus.UNAUTHORIZED.value(), "AUTHENTICATION_REQUIRED"));
        mockMvc.perform(post("/api/v1/meal-usages/{mealUsageId}/rejections", usage.id().value())
                .with(user("store-staff").roles("STORE_STAFF")))
            .andExpect(problem(HttpStatus.FORBIDDEN.value(), "CSRF_TOKEN_INVALID"));
        mockMvc.perform(post("/api/v1/meal-usages/{mealUsageId}/rejections", usage.id().value())
                .with(user("partner").roles("PARTNER"))
                .with(csrf()))
            .andExpect(problem(HttpStatus.FORBIDDEN.value(), "ACCESS_DENIED"));
        mockMvc.perform(post("/api/v1/meal-usages/{mealUsageId}/rejections", usage.id().value())
                .cookie(otherStoreSession.cookie())
                .header("X-CSRF-TOKEN", csrfToken(otherStoreSession)))
            .andExpect(problem(HttpStatus.NOT_FOUND.value(), "MEAL_USAGE_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/meal-usages/{mealUsageId}/rejections", usage.id().value())
                .cookie(session.cookie())
                .header("X-CSRF-TOKEN", csrfToken(session)))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/meal-usages/{mealUsageId}/rejections", usage.id().value())
                .cookie(session.cookie())
                .header("X-CSRF-TOKEN", csrfToken(session)))
            .andExpect(problem(HttpStatus.CONFLICT.value(), "MEAL_USAGE_NOT_PENDING"));
    }

    @Test
    void concurrentAuthenticatedConfirmationAndRejectionLeaveOneTerminalStateAndRollbackRejectedAllocation() throws Exception {
        seedAccount("store-hk", "correct-password", STORE_ID);
        MealContract contract = contract(STORE_ID, 10_000);
        MealUsage usage = pendingUsage(STORE_ID, contract.id());
        mealContractRepository.save(contract);
        mealUsageRepository.save(usage);
        SessionHandle confirmationSession = authenticatedSession("store-hk", "correct-password");
        SessionHandle rejectionSession = authenticatedSession("store-hk", "correct-password");
        String confirmationCsrfToken = csrfToken(confirmationSession);
        String rejectionCsrfToken = csrfToken(rejectionSession);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> confirmation = executor.submit(() -> {
                ready.countDown();
                await(start);
                return mockMvc.perform(post("/api/v1/meal-usages/{mealUsageId}/confirmations", usage.id().value())
                        .cookie(confirmationSession.cookie())
                        .header("X-CSRF-TOKEN", confirmationCsrfToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmerInitials\":\"HK\"}"))
                    .andReturn();
            });
            Future<MvcResult> rejection = executor.submit(() -> {
                ready.countDown();
                await(start);
                return mockMvc.perform(post("/api/v1/meal-usages/{mealUsageId}/rejections", usage.id().value())
                        .cookie(rejectionSession.cookie())
                        .header("X-CSRF-TOKEN", rejectionCsrfToken))
                    .andReturn();
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Integer> statuses = List.of(
                confirmation.get(10, TimeUnit.SECONDS).getResponse().getStatus(),
                rejection.get(10, TimeUnit.SECONDS).getResponse().getStatus()
            );
            assertThat(statuses).containsOnly(HttpStatus.CREATED.value(), HttpStatus.CONFLICT.value());
            assertThat(statuses.stream().filter(status -> status == HttpStatus.CREATED.value()).count()).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        MealUsage terminal = mealUsageRepository.findById(usage.id()).orElseThrow();
        assertThat(terminal.status()).isIn(MealUsageStatus.CONFIRMED, MealUsageStatus.REJECTED);
        if (terminal.status() == MealUsageStatus.REJECTED) {
            assertThat(terminal.rejection()).hasValueSatisfying(audit -> assertThat(audit.staffLoginId()).isEqualTo("store-hk"));
            assertThat(terminal.confirmation()).isEmpty();
            assertThat(terminal.prepaidAllocation()).isEmpty();
            assertThat(jdbcTemplate.queryForObject(
                "select prepaid_balance from meal_contracts where id = ?", Long.class, contract.id().value()
            )).isEqualTo(10_000);
        }
    }

    private SessionHandle authenticatedSession(String loginId, String password) throws Exception {
        SessionHandle session = csrfSession();
        MvcResult login = mockMvc.perform(post("/api/v1/sessions")
                .cookie(session.cookie())
                .header("X-CSRF-TOKEN", csrfToken(session))
                .param("loginId", loginId)
                .param("password", password))
            .andExpect(status().isNoContent())
            .andReturn();
        return StoreOnboardingHttpIntegrationSupport.authenticatedSession(mockMvc, objectMapper, login);
    }

    private SessionHandle csrfSession() throws Exception {
        return StoreOnboardingHttpIntegrationSupport.csrfSession(mockMvc, objectMapper);
    }

    private String csrfToken(SessionHandle session) throws Exception {
        return StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, session);
    }

    private void seedAccount(String loginId, String password, StoreId storeId) {
        jdbcTemplate.update(
            "insert into store_accounts (login_id, password_hash, store_id, enabled) values (?, ?, ?, true)",
            loginId,
            passwordEncoder.encode(password),
            storeId.value()
        );
    }

    private MealContract contract(StoreId storeId, long balance) {
        return new MealContract(
            new MealContractId(UUID.randomUUID()),
            storeId,
            MealContractPaymentType.PREPAID_WITH_RECEIVABLE_OVERFLOW,
            balance
        );
    }

    private MealUsage pendingUsage(StoreId storeId, MealContractId contractId) {
        return MealUsage.pending(
            new MealUsageId(UUID.randomUUID()),
            storeId,
            contractId,
            EntrySource.PARTNER_MOBILE,
            12_000,
            Instant.parse("2026-08-09T01:00:00Z")
        );
    }

    private org.springframework.test.web.servlet.ResultMatcher problem(int expectedStatus, String errorCode) {
        return result -> {
            status().is(expectedStatus).match(result);
            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON).match(result);
            jsonPath("$.errorCode").value(errorCode).match(result);
        };
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent authenticated requests did not receive the start signal");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent authenticated request was interrupted", exception);
        }
    }
}
