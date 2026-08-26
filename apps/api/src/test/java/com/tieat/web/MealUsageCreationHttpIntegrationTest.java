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
import com.tieat.ledger.domain.MealUsageRepository;
import com.tieat.ledger.domain.MealUsageStatus;
import com.tieat.partnership.domain.MealContract;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.partnership.domain.MealContractPaymentType;
import com.tieat.partnership.domain.MealContractRepository;
import com.tieat.store.domain.StoreId;
import com.tieat.web.StoreOnboardingHttpIntegrationSupport.SessionHandle;
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
class MealUsageCreationHttpIntegrationTest {

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
        jdbcTemplate.update("delete from meal_usages");
        jdbcTemplate.update("delete from meal_contracts");
        jdbcTemplate.update("delete from store_accounts");
    }

    @Test
    void createsPendingStoreTabletUsageAgainstPostgresWithoutChangingPrepaidBalance() throws Exception {
        seedAccount("store-hk", "correct-password", STORE_ID);
        MealContract contract = prepaidContract(STORE_ID, 10_000);
        mealContractRepository.save(contract);
        SessionHandle session = authenticatedSession("store-hk", "correct-password");
        String csrfToken = csrfToken(session);

        MvcResult result = mockMvc.perform(createRequest(
                session,
                csrfToken,
                "{\"mealContractId\":\"" + contract.id().value() + "\",\"amountMinor\":12000}"
            ))
            .andExpect(status().isCreated())
            .andExpect(header().exists(HttpHeaders.LOCATION))
            .andExpect(jsonPath("$.mealUsageId").isNotEmpty())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.amountMinor").value(12_000))
            .andExpect(jsonPath("$.createdAt").isNotEmpty())
            .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID usageId = UUID.fromString(response.get("mealUsageId").asText());
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
            .isEqualTo("http://localhost/api/v1/meal-usages/" + usageId);
        assertThat(mealUsageRepository.findById(new com.tieat.ledger.domain.MealUsageId(usageId))).hasValueSatisfying(usage -> {
            assertThat(usage.storeId()).isEqualTo(STORE_ID);
            assertThat(usage.mealContractId()).isEqualTo(contract.id());
            assertThat(usage.entrySource()).isEqualTo(EntrySource.STORE_TABLET);
            assertThat(usage.amount()).isEqualTo(12_000);
            assertThat(usage.status()).isEqualTo(MealUsageStatus.PENDING);
        });
        assertThat(jdbcTemplate.queryForObject("select prepaid_balance from meal_contracts where id = ?", Long.class, contract.id().value()))
            .isEqualTo(10_000);
    }

    @Test
    void rejectsMalformedAndClientControlledBodiesBeforeSaving() throws Exception {
        seedAccount("store-hk", "correct-password", STORE_ID);
        SessionHandle session = authenticatedSession("store-hk", "correct-password");
        String csrfToken = csrfToken(session);

        for (String body : new String[] {
            "{}",
            "{\"mealContractId\":null,\"amountMinor\":12000}",
            "{\"mealContractId\":\"not-a-uuid\",\"amountMinor\":12000}",
            "{\"mealContractId\":\"" + UUID.randomUUID() + "\",\"amountMinor\":0}",
            "{\"mealContractId\":\"" + UUID.randomUUID() + "\",\"amountMinor\":-1}",
            "{\"mealContractId\":\"" + UUID.randomUUID() + "\",\"amountMinor\":12000,\"storeId\":\"" + OTHER_STORE_ID.value() + "\"}",
            "{\"mealContractId\":\"" + UUID.randomUUID() + "\",\"amountMinor\":12000,\"entrySource\":\"PARTNER_MOBILE\"}",
            "{\"mealContractId\":\"" + UUID.randomUUID() + "\",\"amountMinor\":12000,\"createdAt\":\"2020-01-01T00:00:00Z\"}",
            "{\"mealContractId\":\"" + UUID.randomUUID() + "\",\"amountMinor\":12000,\"balance\":999999}"
        }) {
            mockMvc.perform(createRequest(session, csrfToken, body))
                .andExpect(problem(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED"));
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from meal_usages", Long.class)).isZero();
    }

    @Test
    void hidesMissingAndCrossStoreContractsWithTheSameNotFoundProblem() throws Exception {
        seedAccount("store-hk", "correct-password", STORE_ID);
        MealContract crossStoreContract = prepaidContract(OTHER_STORE_ID, 10_000);
        mealContractRepository.save(crossStoreContract);
        SessionHandle session = authenticatedSession("store-hk", "correct-password");
        String csrfToken = csrfToken(session);

        mockMvc.perform(createRequest(session, csrfToken, "{\"mealContractId\":\"" + UUID.randomUUID() + "\",\"amountMinor\":12000}"))
            .andExpect(problem(HttpStatus.NOT_FOUND.value(), "MEAL_CONTRACT_NOT_FOUND"));
        mockMvc.perform(createRequest(session, csrfToken, "{\"mealContractId\":\"" + crossStoreContract.id().value() + "\",\"amountMinor\":12000}"))
            .andExpect(problem(HttpStatus.NOT_FOUND.value(), "MEAL_CONTRACT_NOT_FOUND"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from meal_usages", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select prepaid_balance from meal_contracts where id = ?", Long.class, crossStoreContract.id().value()))
            .isEqualTo(10_000);
    }

    @Test
    void rejectsUnauthenticatedCsrfMissingAndWrongRoleRequests() throws Exception {
        String body = "{\"mealContractId\":\"" + UUID.randomUUID() + "\",\"amountMinor\":12000}";
        mockMvc.perform(post("/api/v1/meal-usages").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(problem(HttpStatus.UNAUTHORIZED.value(), "AUTHENTICATION_REQUIRED"));
        mockMvc.perform(post("/api/v1/meal-usages")
                .with(user("store-staff").roles("STORE_STAFF"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(problem(HttpStatus.FORBIDDEN.value(), "CSRF_TOKEN_INVALID"));
        mockMvc.perform(post("/api/v1/meal-usages")
                .with(user("partner").roles("PARTNER"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(problem(HttpStatus.FORBIDDEN.value(), "ACCESS_DENIED"));
    }

    @Test
    void exposesExactCreationRequestAndProblemResponsesInOpenApiDocument() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/v1/meal-usages'].post").exists())
            .andExpect(jsonPath("$.components.schemas.CreationRequest.properties.mealContractId").exists())
            .andExpect(jsonPath("$.components.schemas.CreationRequest.properties.amountMinor").exists())
            .andExpect(jsonPath("$.components.schemas.CreationRequest.properties.length()").value(2))
            .andExpect(jsonPath("$.paths['/api/v1/meal-usages'].post.responses['201']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/meal-usages'].post.responses['400']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/meal-usages'].post.responses['401']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/meal-usages'].post.responses['403']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/meal-usages'].post.responses['404']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/meal-usages'].post.responses['500']").exists())
            .andExpect(jsonPath("$.components.schemas.ProblemResponse.properties.errorCode").exists());
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

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder createRequest(
        SessionHandle session,
        String csrfToken,
        String body
    ) {
        return post("/api/v1/meal-usages")
            .cookie(session.cookie())
            .header("X-CSRF-TOKEN", csrfToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
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

    private void seedAccount(String loginId, String password, StoreId storeId) {
        jdbcTemplate.update(
            "insert into store_accounts (login_id, password_hash, store_id, enabled) values (?, ?, ?, true)",
            loginId,
            passwordEncoder.encode(password),
            storeId.value()
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
