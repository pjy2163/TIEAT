package com.tieat.web;

import static com.tieat.web.MonthlyMealUsageHttpIntegrationFixture.OTHER_STORE_ID;
import static com.tieat.web.MonthlyMealUsageHttpIntegrationFixture.STORE_ID;
import static com.tieat.web.MonthlyMealUsageHttpIntegrationFixture.cancelled;
import static com.tieat.web.MonthlyMealUsageHttpIntegrationFixture.confirmed;
import static com.tieat.web.MonthlyMealUsageHttpIntegrationFixture.pending;
import static com.tieat.web.MonthlyMealUsageHttpIntegrationFixture.rejected;
import static com.tieat.web.MonthlyMealUsageHttpIntegrationFixture.seedAccount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tieat.ledger.domain.MealUsage;
import com.tieat.ledger.domain.MealUsageRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MonthlyMealUsageHttpIntegrationTest {

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
    private MealUsageRepository mealUsageRepository;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void clearDatabase() {
        jdbcTemplate.update("delete from public_meal_usage_idempotency_keys");
        jdbcTemplate.update("delete from pos_settlement_allocations");
        jdbcTemplate.update("delete from pos_settlements");
        jdbcTemplate.update("delete from meal_usages");
        jdbcTemplate.update("delete from meal_contracts");
        jdbcTemplate.update("delete from meal_usage_qr_contexts");
        jdbcTemplate.update("delete from store_accounts");
    }

    @Test
    void listsOnlyAuthenticatedStoreConfirmedRowsWithinKstCalendarMonthInDescendingLedgerOrder() throws Exception {
        seedAccount(jdbcTemplate, passwordEncoder, "store-hk", "correct-password", STORE_ID);
        MealUsage monthStartConfirmed = confirmed(
            STORE_ID, "00000000-0000-0000-0000-000000000001", "2026-07-31T15:00:00Z", "협력사 시작"
        );
        MealUsage sameMomentLowerIdConfirmed = confirmed(
            STORE_ID, "00000000-0000-0000-0000-000000000002", "2026-08-15T01:00:00Z", "협력사 중간"
        );
        MealUsage sameMomentHigherIdConfirmed = confirmed(
            STORE_ID, "00000000-0000-0000-0000-000000000003", "2026-08-15T01:00:00Z", "협력사 확정"
        );
        MealUsage newestPending = pending(
            STORE_ID, "00000000-0000-0000-0000-000000000004", "2026-08-20T01:00:00Z"
        );
        MealUsage newerRejected = rejected(
            STORE_ID, "00000000-0000-0000-0000-000000000005", "2026-08-19T01:00:00Z", null
        );
        MealUsage newerCancelled = cancelled(
            jdbcTemplate, STORE_ID, "00000000-0000-0000-0000-000000000006", "2026-08-18T01:00:00Z", "2026-09-01T00:00:00Z"
        );
        mealUsageRepository.save(monthStartConfirmed);
        mealUsageRepository.save(sameMomentLowerIdConfirmed);
        mealUsageRepository.save(sameMomentHigherIdConfirmed);
        mealUsageRepository.save(newestPending);
        mealUsageRepository.save(newerRejected);
        mealUsageRepository.save(newerCancelled);
        mealUsageRepository.save(confirmed(
            STORE_ID, "00000000-0000-0000-0000-000000000007", "2026-07-31T14:59:59.999999Z", "경계 전"
        ));
        mealUsageRepository.save(confirmed(
            STORE_ID, "00000000-0000-0000-0000-000000000008", "2026-08-31T15:00:00Z", "경계 후"
        ));
        MealUsage previousMonthConfirmed = confirmed(
            STORE_ID, "00000000-0000-0000-0000-000000000010", "2026-06-30T15:00:00Z", "협력사 이전"
        );
        mealUsageRepository.save(previousMonthConfirmed);
        mealUsageRepository.save(confirmed(
            OTHER_STORE_ID, "00000000-0000-0000-0000-000000000009", "2026-08-31T14:59:59.999999Z", "다른 매장"
        ));
        MockHttpSession session = authenticatedSession("store-hk", "correct-password");
        long initialCount = jdbcTemplate.queryForObject("select count(*) from meal_usages", Long.class);
        long initialVersionSum = jdbcTemplate.queryForObject("select coalesce(sum(version), 0) from meal_usages", Long.class);

        MvcResult firstPage = mockMvc.perform(monthlyRequest(session, "2026-08", 0, 2))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.month").value("2026-08"))
            .andExpect(jsonPath("$.fromMonth").value("2026-08"))
            .andExpect(jsonPath("$.toMonth").value("2026-08"))
            .andExpect(jsonPath("$.timeZone").value("Asia/Seoul"))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].id").value(sameMomentHigherIdConfirmed.id().value().toString()))
            .andExpect(jsonPath("$.items[0].status").value("CONFIRMED"))
            .andExpect(jsonPath("$.items[0].settlementStatus").value("PAYMENT_DUE"))
            .andExpect(jsonPath("$.items[0].partnerDisplayName").value("협력사 확정"))
            .andExpect(jsonPath("$.items[0].confirmedStaffInitials").value("HK"))
            .andExpect(jsonPath("$.items[1].id").value(sameMomentLowerIdConfirmed.id().value().toString()))
            .andExpect(jsonPath("$.items[1].status").value("CONFIRMED"))
            .andExpect(jsonPath("$.items[1].settlementStatus").value("PAYMENT_DUE"))
            .andExpect(jsonPath("$.items[1].confirmedStaffInitials").value("HK"))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(2))
            .andExpect(jsonPath("$.hasNext").value(true))
            .andExpect(jsonPath("$.totalAmountMinor").value(36_000))
            .andReturn();

        JsonNode firstPageJson = objectMapper.readTree(firstPage.getResponse().getContentAsString());
        assertThat(fieldNames(firstPageJson)).containsExactlyInAnyOrder(
            "month", "fromMonth", "toMonth", "timeZone", "items", "page", "size", "hasNext", "totalAmountMinor"
        );
        assertThat(fieldNames(firstPageJson.get("items").get(0)))
            .containsExactlyInAnyOrder(
                "id", "status", "partnerDisplayName", "amountMinor", "createdAt", "confirmedStaffInitials", "settlementStatus"
            );

        mockMvc.perform(monthlyRequest(session, "2026-08", 1, 2))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id").value(monthStartConfirmed.id().value().toString()))
            .andExpect(jsonPath("$.items[0].status").value("CONFIRMED"))
            .andExpect(jsonPath("$.items[0].settlementStatus").value("PAYMENT_DUE"))
            .andExpect(jsonPath("$.items[0].confirmedStaffInitials").value("HK"))
            .andExpect(jsonPath("$.hasNext").value(false));

        mockMvc.perform(monthlyRequest(session, "2026-07", "2026-08", 0, 20))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.month").value("2026-07"))
            .andExpect(jsonPath("$.fromMonth").value("2026-07"))
            .andExpect(jsonPath("$.toMonth").value("2026-08"))
            .andExpect(jsonPath("$.items.length()").value(5))
            .andExpect(jsonPath("$.items[4].id").value(previousMonthConfirmed.id().value().toString()))
            .andExpect(jsonPath("$.totalAmountMinor").value(60_000));

        assertThat(jdbcTemplate.queryForObject("select count(*) from meal_usages", Long.class)).isEqualTo(initialCount);
        assertThat(jdbcTemplate.queryForObject("select coalesce(sum(version), 0) from meal_usages", Long.class))
            .isEqualTo(initialVersionSum);
    }

    @Test
    void derivesSettlementStatusFromConfirmedAllocationAndPrepaidSourceValuesWithoutDuplicatingRows() throws Exception {
        seedAccount(jdbcTemplate, passwordEncoder, "store-hk", "correct-password", STORE_ID);
        jdbcTemplate.update(
            "insert into meal_contracts (id, store_id, payment_type, prepaid_balance) values (?, ?, 'POSTPAID', 0)",
            MonthlyMealUsageHttpIntegrationFixture.MEAL_CONTRACT_ID.value(), STORE_ID.value()
        );
        MealUsage unpaid = confirmed(
            STORE_ID, "00000000-0000-0000-0000-000000000011", "2026-08-15T01:00:00Z", "미배분 미수"
        );
        MealUsage recorded = confirmed(
            STORE_ID, "00000000-0000-0000-0000-000000000012", "2026-08-15T02:00:00Z", "기록된 미수"
        );
        MealUsage prepaidOnly = confirmed(
            STORE_ID, "00000000-0000-0000-0000-000000000013", "2026-08-15T03:00:00Z", "선불 처리", 12_000, 0
        );
        MealUsage mixed = confirmed(
            STORE_ID, "00000000-0000-0000-0000-000000000014", "2026-08-15T04:00:00Z", "혼합 미수", 5_000, 7_000
        );
        mealUsageRepository.save(unpaid);
        mealUsageRepository.save(recorded);
        mealUsageRepository.save(prepaidOnly);
        mealUsageRepository.save(mixed);
        recordSettlementAllocation(recorded);

        MockHttpSession session = authenticatedSession("store-hk", "correct-password");
        mockMvc.perform(monthlyRequest(session, "2026-08", 0, 20))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(4))
            .andExpect(jsonPath("$.items[0].id").value(mixed.id().value().toString()))
            .andExpect(jsonPath("$.items[0].settlementStatus").value("PAYMENT_DUE"))
            .andExpect(jsonPath("$.items[1].id").value(prepaidOnly.id().value().toString()))
            .andExpect(jsonPath("$.items[1].settlementStatus").value("PREPAID_SETTLED"))
            .andExpect(jsonPath("$.items[2].id").value(recorded.id().value().toString()))
            .andExpect(jsonPath("$.items[2].settlementStatus").value("PAYMENT_RECORDED"))
            .andExpect(jsonPath("$.items[3].id").value(unpaid.id().value().toString()))
            .andExpect(jsonPath("$.items[3].settlementStatus").value("PAYMENT_DUE"));
    }

    private void recordSettlementAllocation(MealUsage mealUsage) {
        UUID settlementId = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into pos_settlements
                    (id, store_id, meal_contract_id, pos_business_date, submitted_total_minor,
                     recorded_by_login_id, recorded_at, idempotency_key)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
            settlementId,
            STORE_ID.value(),
            MonthlyMealUsageHttpIntegrationFixture.MEAL_CONTRACT_ID.value(),
            java.sql.Date.valueOf(LocalDate.of(2026, 8, 31)),
            mealUsage.amount(),
            "store-hk",
            java.sql.Timestamp.from(Instant.parse("2026-09-01T01:00:00Z")),
            UUID.randomUUID()
        );
        jdbcTemplate.update(
            "insert into pos_settlement_allocations (pos_settlement_id, meal_usage_id, receivable_amount_minor) values (?, ?, ?)",
            settlementId,
            mealUsage.id().value(),
            mealUsage.amount()
        );
    }

    @Test
    void rejectsInvalidQueriesAndEnforcesStaffAuthenticationWithNoStoreProblemResponses() throws Exception {
        seedAccount(jdbcTemplate, passwordEncoder, "store-hk", "correct-password", STORE_ID);
        MockHttpSession session = authenticatedSession("store-hk", "correct-password");

        mockMvc.perform(get("/api/v1/meal-usages/months/2026-08").param("page", "0").param("size", "20"))
            .andExpect(problem(HttpStatus.UNAUTHORIZED.value(), "AUTHENTICATION_REQUIRED"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")));
        mockMvc.perform(get("/api/v1/meal-usages/months/2026-08")
                .param("page", "0")
                .param("size", "20")
                .with(user("partner").roles("PARTNER")))
            .andExpect(problem(HttpStatus.FORBIDDEN.value(), "ACCESS_DENIED"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")));

        for (String pathAndQuery : new String[] {
            "/api/v1/meal-usages/months/2026-13?page=0&size=20",
            "/api/v1/meal-usages/months/not-a-month?page=0&size=20",
            "/api/v1/meal-usages/months/2026-08?page=-1&size=20",
            "/api/v1/meal-usages/months/2026-08?page=0&size=0",
            "/api/v1/meal-usages/months/2026-08?page=0&size=101",
            "/api/v1/meal-usages/months/2026-08?page=0&size=not-a-number",
            "/api/v1/meal-usages/months/2026-08?size=20",
            "/api/v1/meal-usages/months/2026-08?page=0",
            "/api/v1/meal-usages/months/2026-08?to=2026-07&page=0&size=20",
            "/api/v1/meal-usages/months/2026-01?to=2027-01&page=0&size=20"
        }) {
            mockMvc.perform(get(pathAndQuery).session(session))
                .andExpect(problem(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")));
        }
    }

    private MockHttpSession authenticatedSession(String loginId, String password) throws Exception {
        MockHttpSession session = csrfSession();
        MvcResult login = mockMvc.perform(post("/api/v1/sessions")
                .session(session)
                .header("X-CSRF-TOKEN", csrfToken(session))
                .param("loginId", loginId)
                .param("password", password))
            .andExpect(status().isNoContent())
            .andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    private MockHttpSession csrfSession() throws Exception {
        return (MockHttpSession) mockMvc.perform(get("/api/v1/csrf"))
            .andExpect(status().isOk())
            .andReturn()
            .getRequest()
            .getSession(false);
    }

    private String csrfToken(MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/csrf").session(session))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        return response.get("token").asText();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder monthlyRequest(
        MockHttpSession session,
        String month,
        int page,
        int size
    ) {
        return monthlyRequest(session, month, null, page, size);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder monthlyRequest(
        MockHttpSession session,
        String month,
        String to,
        int page,
        int size
    ) {
        var request = get("/api/v1/meal-usages/months/{month}", month)
            .session(session)
            .param("page", Integer.toString(page))
            .param("size", Integer.toString(size));
        if (to != null) {
            request.param("to", to);
        }
        return request;
    }

    private org.springframework.test.web.servlet.ResultMatcher problem(int expectedStatus, String errorCode) {
        return result -> {
            status().is(expectedStatus).match(result);
            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON).match(result);
            jsonPath("$.errorCode").value(errorCode).match(result);
        };
    }

    private Set<String> fieldNames(JsonNode node) {
        return Set.copyOf(node.propertyNames());
    }

}
