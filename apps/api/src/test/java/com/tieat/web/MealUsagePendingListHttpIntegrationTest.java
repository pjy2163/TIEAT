package com.tieat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tieat.ledger.domain.Confirmation;
import com.tieat.ledger.domain.EntrySource;
import com.tieat.ledger.domain.MealUsage;
import com.tieat.ledger.domain.MealUsageId;
import com.tieat.ledger.domain.MealUsageRepository;
import com.tieat.ledger.domain.PrepaidAllocation;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.store.domain.StoreId;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
class MealUsagePendingListHttpIntegrationTest {

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
        jdbcTemplate.update("delete from meal_usages");
        jdbcTemplate.update("delete from store_accounts");
    }

    @Test
    void listsOnlyOwnPendingUsagesAcrossDatesWithDatabaseTieOrderingAndNoWrites() throws Exception {
        seedAccount("store-hk", "correct-password", STORE_ID);
        MealUsage yesterday = pending(STORE_ID, "00000000-0000-0000-0000-000000000001", "2026-08-05T01:00:00Z");
        MealUsage sameTimeFirst = pending(STORE_ID, "00000000-0000-0000-0000-000000000002", "2026-08-06T01:00:00Z");
        MealUsage sameTimeSecond = pending(STORE_ID, "00000000-0000-0000-0000-000000000003", "2026-08-06T01:00:00Z");
        mealUsageRepository.save(yesterday);
        mealUsageRepository.save(sameTimeFirst);
        mealUsageRepository.save(sameTimeSecond);
        mealUsageRepository.save(pending(OTHER_STORE_ID, "00000000-0000-0000-0000-000000000004", "2026-08-04T01:00:00Z"));
        mealUsageRepository.save(confirmed(STORE_ID, "00000000-0000-0000-0000-000000000005", "2026-08-03T01:00:00Z"));
        MockHttpSession session = authenticatedSession("store-hk", "correct-password");
        long initialCount = jdbcTemplate.queryForObject("select count(*) from meal_usages", Long.class);
        long initialVersionSum = jdbcTemplate.queryForObject("select coalesce(sum(version), 0) from meal_usages", Long.class);

        MvcResult firstPage = mockMvc.perform(listRequest(session, "PENDING", 0, 2))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].mealUsageId").value(yesterday.id().value().toString()))
            .andExpect(jsonPath("$.items[1].mealUsageId").value(sameTimeFirst.id().value().toString()))
            .andExpect(jsonPath("$.items[0].status").value("PENDING"))
            .andExpect(jsonPath("$.items[0].entrySource").value("STORE_TABLET"))
            .andExpect(jsonPath("$.items[0].partnerDisplayName").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.items[0].amountMinor").value(12_000))
            .andExpect(jsonPath("$.items[0].createdAt").value("2026-08-05T01:00:00Z"))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(2))
            .andExpect(jsonPath("$.hasNext").value(true))
            .andReturn();
        JsonNode firstPageJson = objectMapper.readTree(firstPage.getResponse().getContentAsString());
        assertThat(fieldNames(firstPageJson)).containsExactlyInAnyOrder("items", "page", "size", "hasNext");
        assertThat(fieldNames(firstPageJson.get("items").get(0)))
            .containsExactlyInAnyOrder("mealUsageId", "status", "entrySource", "partnerDisplayName", "amountMinor", "createdAt");
        mockMvc.perform(listRequest(session, "PENDING", 1, 2))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].mealUsageId").value(sameTimeSecond.id().value().toString()))
            .andExpect(jsonPath("$.hasNext").value(false));
        mockMvc.perform(listRequest(session, "PENDING", 2, 2))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isEmpty())
            .andExpect(jsonPath("$.hasNext").value(false));
        mockMvc.perform(listRequest(session, "PENDING", 0, 2)).andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject("select count(*) from meal_usages", Long.class)).isEqualTo(initialCount);
        assertThat(jdbcTemplate.queryForObject("select coalesce(sum(version), 0) from meal_usages", Long.class))
            .isEqualTo(initialVersionSum);
    }

    @Test
    void rejectsInvalidOrMissingQueryParametersAndEnforcesAuthenticationAndRole() throws Exception {
        seedAccount("store-hk", "correct-password", STORE_ID);
        MockHttpSession session = authenticatedSession("store-hk", "correct-password");

        mockMvc.perform(get("/api/v1/meal-usages"))
            .andExpect(problem(HttpStatus.UNAUTHORIZED.value(), "AUTHENTICATION_REQUIRED"));
        mockMvc.perform(get("/api/v1/meal-usages?status=PENDING&page=0&size=50")
                .with(user("partner").roles("PARTNER")))
            .andExpect(problem(HttpStatus.FORBIDDEN.value(), "ACCESS_DENIED"));
        for (String query : new String[] {
            "status=CONFIRMED&page=0&size=50",
            "status=PENDING&page=-1&size=50",
            "status=PENDING&page=0&size=0",
            "status=PENDING&page=0&size=101",
            "status=PENDING&page=0&size=not-a-number",
            "page=0&size=50",
            "status=PENDING&size=50",
            "status=PENDING&page=0"
        }) {
            mockMvc.perform(get("/api/v1/meal-usages?" + query).session(session))
                .andExpect(problem(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED"));
        }
        mockMvc.perform(listRequest(session, "PENDING", 0, 1))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isEmpty())
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(1));
        mockMvc.perform(listRequest(session, "PENDING", 0, 100)).andExpect(status().isOk());
    }

    @Test
    void exposesExactPendingListContractAndPreservesPostContractInOpenApi() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/v1/meal-usages'].get").exists())
            .andExpect(jsonPath("$.paths['/api/v1/meal-usages'].get.parameters[?(@.name == 'status')].schema.enum[0]").value("PENDING"))
            .andExpect(jsonPath("$.paths['/api/v1/meal-usages'].get.parameters[?(@.name == 'page')].schema.minimum").value(0))
            .andExpect(jsonPath("$.paths['/api/v1/meal-usages'].get.parameters[?(@.name == 'size')].schema.minimum").value(1))
            .andExpect(jsonPath("$.paths['/api/v1/meal-usages'].get.parameters[?(@.name == 'size')].schema.maximum").value(100))
            .andExpect(jsonPath("$.paths['/api/v1/meal-usages'].get.responses['200']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/meal-usages'].get.responses['400']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/meal-usages'].get.responses['401']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/meal-usages'].get.responses['403']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/meal-usages'].post.responses['201']").exists())
            .andReturn();
        JsonNode document = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(fieldNames(document.at("/components/schemas/PendingMealUsageListResponse/properties")))
            .containsExactlyInAnyOrder("items", "page", "size", "hasNext");
        assertThat(fieldNames(document.at("/components/schemas/PendingMealUsageItemResponse/properties")))
            .containsExactlyInAnyOrder("mealUsageId", "status", "entrySource", "partnerDisplayName", "amountMinor", "createdAt");
    }

    @Test
    void returnsPartnerDisplayNameSnapshotForTheAuthenticatedStorePendingItem() throws Exception {
        seedAccount("store-hk", "correct-password", STORE_ID);
        MealUsage usage = MealUsage.restorePending(
            new MealUsageId(UUID.fromString("c89c2660-84f0-4a8c-a7ff-0eec1725ab5b")),
            STORE_ID,
            new MealContractId(UUID.fromString("8cb73a47-d5c5-4f7a-8db0-b61e171c4f0a")),
            EntrySource.PARTNER_MOBILE,
            12_000,
            Instant.parse("2026-08-06T01:00:00Z"),
            0,
            "협력사 A",
            null
        );
        mealUsageRepository.save(usage);
        MockHttpSession session = authenticatedSession("store-hk", "correct-password");

        mockMvc.perform(listRequest(session, "PENDING", 0, 50))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].partnerDisplayName").value("협력사 A"));
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

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder listRequest(
        MockHttpSession session,
        String status,
        int page,
        int size
    ) {
        return get("/api/v1/meal-usages")
            .session(session)
            .param("status", status)
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

    private void seedAccount(String loginId, String password, StoreId storeId) {
        jdbcTemplate.update(
            "insert into store_accounts (login_id, password_hash, store_id, enabled) values (?, ?, ?, true)",
            loginId,
            passwordEncoder.encode(password),
            storeId.value()
        );
    }

    private MealUsage pending(StoreId storeId, String id, String createdAt) {
        return MealUsage.pending(
            new MealUsageId(UUID.fromString(id)),
            storeId,
            new MealContractId(UUID.fromString("8cb73a47-d5c5-4f7a-8db0-b61e171c4f0a")),
            EntrySource.STORE_TABLET,
            12_000,
            Instant.parse(createdAt)
        );
    }

    private MealUsage confirmed(StoreId storeId, String id, String createdAt) {
        return MealUsage.restoreConfirmed(
            new MealUsageId(UUID.fromString(id)),
            storeId,
            new MealContractId(UUID.fromString("8cb73a47-d5c5-4f7a-8db0-b61e171c4f0a")),
            EntrySource.STORE_TABLET,
            12_000,
            Instant.parse(createdAt),
            0,
            new Confirmation("HK", Instant.parse("2026-08-06T02:00:00Z")),
            new PrepaidAllocation(12_000, 0, 12_000, 0)
        );
    }
}
