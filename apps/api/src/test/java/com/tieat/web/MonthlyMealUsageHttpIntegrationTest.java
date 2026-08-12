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
import java.util.Set;
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
        jdbcTemplate.update("delete from meal_usages");
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
            .andExpect(jsonPath("$.timeZone").value("Asia/Seoul"))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].id").value(sameMomentHigherIdConfirmed.id().value().toString()))
            .andExpect(jsonPath("$.items[0].status").value("CONFIRMED"))
            .andExpect(jsonPath("$.items[0].partnerDisplayName").value("협력사 확정"))
            .andExpect(jsonPath("$.items[0].confirmedStaffInitials").value("HK"))
            .andExpect(jsonPath("$.items[1].id").value(sameMomentLowerIdConfirmed.id().value().toString()))
            .andExpect(jsonPath("$.items[1].status").value("CONFIRMED"))
            .andExpect(jsonPath("$.items[1].confirmedStaffInitials").value("HK"))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(2))
            .andExpect(jsonPath("$.hasNext").value(true))
            .andReturn();

        JsonNode firstPageJson = objectMapper.readTree(firstPage.getResponse().getContentAsString());
        assertThat(fieldNames(firstPageJson)).containsExactlyInAnyOrder("month", "timeZone", "items", "page", "size", "hasNext");
        assertThat(fieldNames(firstPageJson.get("items").get(0)))
            .containsExactlyInAnyOrder(
                "id", "status", "partnerDisplayName", "amountMinor", "createdAt", "confirmedStaffInitials"
            );

        mockMvc.perform(monthlyRequest(session, "2026-08", 1, 2))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id").value(monthStartConfirmed.id().value().toString()))
            .andExpect(jsonPath("$.items[0].status").value("CONFIRMED"))
            .andExpect(jsonPath("$.items[0].confirmedStaffInitials").value("HK"))
            .andExpect(jsonPath("$.hasNext").value(false));

        assertThat(jdbcTemplate.queryForObject("select count(*) from meal_usages", Long.class)).isEqualTo(initialCount);
        assertThat(jdbcTemplate.queryForObject("select coalesce(sum(version), 0) from meal_usages", Long.class))
            .isEqualTo(initialVersionSum);
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
            "/api/v1/meal-usages/months/2026-08?page=0"
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
        return get("/api/v1/meal-usages/months/{month}", month)
            .session(session)
            .param("page", Integer.toString(page))
            .param("size", Integer.toString(size));
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
