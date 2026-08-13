package com.tieat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tieat.partnership.domain.MealContract;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.partnership.domain.MealContractPaymentType;
import com.tieat.partnership.domain.MealContractRepository;
import com.tieat.partnership.domain.PartnerOrganization;
import com.tieat.partnership.domain.PartnerOrganizationId;
import com.tieat.ledger.domain.PublicMealUsageIdempotency;
import com.tieat.partnership.domain.PartnerOrganizationRepository;
import com.tieat.ledger.application.ConfirmMealUsageCommand;
import com.tieat.ledger.application.ConfirmMealUsageUseCase;
import com.tieat.ledger.application.MealUsageNotPendingException;
import com.tieat.ledger.application.PublicMealUsageRequestUseCase;
import com.tieat.ledger.domain.MealUsageId;
import com.tieat.qr.domain.MealUsageQrToken;
import com.tieat.qr.application.PublicMealUsageQrNotFoundException;
import com.tieat.store.domain.StoreId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
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
class PublicMealUsageQrHttpIntegrationTest {

    private static final StoreId STORE_ID = new StoreId(UUID.fromString("9d5e37dd-dbe2-40dc-97fb-8e77c89aa4cb"));
    private static final StoreId OTHER_STORE_ID = new StoreId(UUID.fromString("6142be7d-0dc9-4f77-a17d-07e1e5c6e9a1"));
    private static final String PUBLIC_REQUEST_KEY = MealUsageQrToken.generate();
    private static final String CUSTOMER_NAME = "홍길동";

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
    private PartnerOrganizationRepository partnerOrganizationRepository;

    @Autowired
    private MealContractRepository mealContractRepository;

    @Autowired
    private ConfirmMealUsageUseCase confirmMealUsageUseCase;

    @Autowired
    private PublicMealUsageRequestUseCase publicMealUsageRequestUseCase;

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
        jdbcTemplate.update("delete from meal_contracts");
        jdbcTemplate.update("delete from partner_organizations");
        jdbcTemplate.update("delete from store_accounts");
    }

    @Test
    void exposesOnlyTheQrStoreAndActivePartnerChoicesWithoutCaching() throws Exception {
        Fixture fixture = activeFixture();
        PartnerOrganization excludedPartner = partner("비활성 협력사");
        mealContractRepository.save(new MealContract(
            new MealContractId(UUID.randomUUID()),
            STORE_ID,
            MealContractPaymentType.POSTPAID,
            0,
            excludedPartner.id(),
            false
        ));
        PartnerOrganization otherStorePartner = partner("다른 매장 협력사");
        mealContractRepository.save(new MealContract(
            new MealContractId(UUID.randomUUID()),
            OTHER_STORE_ID,
            MealContractPaymentType.POSTPAID,
            0,
            otherStorePartner.id(),
            true
        ));

        MvcResult result = mockMvc.perform(get(path(fixture.token)))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.storeDisplayName").value("강남점"))
            .andExpect(jsonPath("$.partners.length()").value(1))
            .andExpect(jsonPath("$.partners[0].mealContractId").value(fixture.contract.id().value().toString()))
            .andExpect(jsonPath("$.partners[0].partnerDisplayName").value("협력사 A"))
            .andExpect(jsonPath("$.qrExpiresAt").isNotEmpty())
            .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(fieldNames(response)).containsExactlyInAnyOrder("storeDisplayName", "partners", "qrExpiresAt");
        assertThat(fieldNames(response.get("partners").get(0)))
            .containsExactlyInAnyOrder("mealContractId", "partnerDisplayName");
        assertThat(result.getResponse().getContentAsString())
            .doesNotContain("balance", "history", "staff", "owner", OTHER_STORE_ID.value().toString());
    }

    @Test
    void returnsTheSameTokenFreeNotFoundProblemForUnknownRevokedExpiredAndMalformedTokens() throws Exception {
        Fixture fixture = activeFixture();
        String unknownToken = MealUsageQrToken.generate();
        String revokedToken = MealUsageQrToken.generate();
        String expiredToken = MealUsageQrToken.generate();
        String malformedToken = "not_a_256_bit_qr_token";
        seedQrContext(revokedToken, STORE_ID, Instant.now().plusSeconds(3_600), Instant.now());
        seedQrContext(expiredToken, OTHER_STORE_ID, Instant.now().minusSeconds(1), null);

        List<String> problems = new ArrayList<>();
        for (String token : List.of(unknownToken, revokedToken, expiredToken, malformedToken)) {
            MvcResult result = mockMvc.perform(get(path(token)))
                .andExpect(publicProblem(HttpStatus.NOT_FOUND.value(), "PUBLIC_MEAL_USAGE_QR_NOT_FOUND", token))
                .andReturn();
            problems.add(result.getResponse().getContentAsString());
        }

        assertThat(problems).allSatisfy(body -> {
            assertThat(body).doesNotContain("revoked", "expired", "tokenHash");
        });
        assertThat(Set.copyOf(problems)).hasSize(1);

        for (String token : List.of(unknownToken, revokedToken, expiredToken, malformedToken)) {
            mockMvc.perform(publicCreate(token, UUID.randomUUID(), fixture.contract.id().value(), 1_000))
                .andExpect(publicProblem(HttpStatus.NOT_FOUND.value(), "PUBLIC_MEAL_USAGE_QR_NOT_FOUND", token));
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from meal_usages", Long.class)).isZero();
    }

    @Test
    void createsOnePublicPendingUsageFromServerDerivedScopeAndLeavesBalanceUntouched() throws Exception {
        Fixture fixture = activeFixture();
        UUID idempotencyKey = UUID.randomUUID();

        MvcResult result = mockMvc.perform(publicCreate(fixture.token, idempotencyKey, fixture.contract.id().value(), 12_000))
            .andExpect(status().isCreated())
            .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.mealUsageId").isNotEmpty())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.amountMinor").value(12_000))
            .andExpect(jsonPath("$.createdAt").isNotEmpty())
            .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(fieldNames(response)).containsExactlyInAnyOrder("mealUsageId", "status", "amountMinor", "createdAt");
        UUID mealUsageId = UUID.fromString(response.get("mealUsageId").asText());
        assertThat(jdbcTemplate.queryForObject(
            "select entry_source from meal_usages where id = ?", String.class, mealUsageId
        )).isEqualTo("PARTNER_MOBILE");
        assertThat(jdbcTemplate.queryForObject(
            "select store_id from meal_usages where id = ?", UUID.class, mealUsageId
        )).isEqualTo(STORE_ID.value());
        assertThat(jdbcTemplate.queryForObject(
            "select meal_contract_id from meal_usages where id = ?", UUID.class, mealUsageId
        )).isEqualTo(fixture.contract.id().value());
        assertThat(jdbcTemplate.queryForObject(
            "select partner_display_name from meal_usages where id = ?", String.class, mealUsageId
        )).isEqualTo("협력사 A");
        assertThat(jdbcTemplate.queryForObject(
            "select customer_name from meal_usages where id = ?", String.class, mealUsageId
        )).isEqualTo(CUSTOMER_NAME);
        assertThat(jdbcTemplate.queryForObject(
            "select public_qr_context_id from meal_usages where id = ?", UUID.class, mealUsageId
        )).isEqualTo(fixture.qrContextId);
        assertThat(jdbcTemplate.queryForObject(
            "select prepaid_balance from meal_contracts where id = ?", Long.class, fixture.contract.id().value()
        )).isEqualTo(10_000);
        String requestKeyHash = jdbcTemplate.queryForObject(
            "select request_key_hash from public_meal_usage_idempotency_keys where qr_context_id = ? and idempotency_key = ?",
            String.class,
            fixture.qrContextId,
            idempotencyKey
        );
        assertThat(requestKeyHash).isEqualTo(PublicMealUsageIdempotency.hashRequestKey(PUBLIC_REQUEST_KEY));
        assertThat(result.getResponse().getContentAsString()).doesNotContain(PUBLIC_REQUEST_KEY, requestKeyHash, CUSTOMER_NAME);
        mockMvc.perform(get("/api/v1/meal-usages?status=PENDING&page=0&size=50"))
            .andExpect(problem(HttpStatus.UNAUTHORIZED.value(), "AUTHENTICATION_REQUIRED"));
        mockMvc.perform(post("/api/v1/meal-usages/{mealUsageId}/confirmations", mealUsageId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmerInitials\":\"HK\"}"))
            .andExpect(problem(HttpStatus.UNAUTHORIZED.value(), "AUTHENTICATION_REQUIRED"));
        mockMvc.perform(post("/api/v1/meal-usages/{mealUsageId}/rejections", mealUsageId))
            .andExpect(problem(HttpStatus.UNAUTHORIZED.value(), "AUTHENTICATION_REQUIRED"));
        mockMvc.perform(get(path(fixture.token) + "/meal-usages"))
            .andExpect(problem(HttpStatus.UNAUTHORIZED.value(), "AUTHENTICATION_REQUIRED"));
    }

    @Test
    void replaysSameQrContextKeyAndPayloadButRejectsChangedPayload() throws Exception {
        Fixture fixture = activeFixture();
        UUID idempotencyKey = UUID.randomUUID();

        MvcResult first = mockMvc.perform(publicCreate(fixture.token, idempotencyKey, fixture.contract.id().value(), 8_500))
            .andExpect(status().isCreated())
            .andReturn();
        MvcResult replay = mockMvc.perform(publicCreate(fixture.token, idempotencyKey, fixture.contract.id().value(), 8_500))
            .andExpect(status().isCreated())
            .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
            .andReturn();
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(jdbcTemplate.queryForObject("select count(*) from meal_usages", Long.class)).isEqualTo(1);

        mockMvc.perform(publicCreate(fixture.token, idempotencyKey, fixture.contract.id().value(), 8_501))
            .andExpect(publicProblem(HttpStatus.CONFLICT.value(), "IDEMPOTENCY_KEY_REUSED", fixture.token));
        mockMvc.perform(publicCreate(fixture.token, idempotencyKey, UUID.randomUUID(), 8_500))
            .andExpect(publicProblem(HttpStatus.CONFLICT.value(), "IDEMPOTENCY_KEY_REUSED", fixture.token));
        mockMvc.perform(publicCreate(
            fixture.token, idempotencyKey, fixture.contract.id().value(), "김길동", 8_500, PUBLIC_REQUEST_KEY
        )).andExpect(publicProblem(HttpStatus.CONFLICT.value(), "IDEMPOTENCY_KEY_REUSED", fixture.token));
        mockMvc.perform(publicCreate(
            fixture.token, idempotencyKey, fixture.contract.id().value(), 8_500, MealUsageQrToken.generate()
        )).andExpect(publicProblem(HttpStatus.CONFLICT.value(), "IDEMPOTENCY_KEY_REUSED", fixture.token));
        assertThat(jdbcTemplate.queryForObject("select count(*) from meal_usages", Long.class)).isEqualTo(1);

        UUID mealUsageId = UUID.fromString(objectMapper.readTree(first.getResponse().getContentAsString()).get("mealUsageId").asText());
        jdbcTemplate.update(
            "update meal_usages set status = 'REJECTED', rejected_staff_login_id = ?, rejected_at = ? where id = ?",
            "store-hk", Timestamp.from(Instant.now()), mealUsageId
        );
        mockMvc.perform(publicCreate(fixture.token, idempotencyKey, fixture.contract.id().value(), 8_500))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(content().string(first.getResponse().getContentAsString()));
    }

    @Test
    void letsTheSamePublicRequestReadAndCancelOnlyItsPendingUsage() throws Exception {
        Fixture fixture = activeFixture();
        UUID idempotencyKey = UUID.randomUUID();
        String publicRequestKey = MealUsageQrToken.generate();
        MvcResult created = mockMvc.perform(publicCreate(
            fixture.token, idempotencyKey, fixture.contract.id().value(), 8_500, publicRequestKey
        )).andExpect(status().isCreated()).andReturn();
        UUID mealUsageId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("mealUsageId").asText());

        mockMvc.perform(publicStatus(fixture.token, idempotencyKey, mealUsageId, publicRequestKey))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.mealUsageId").value(mealUsageId.toString()))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.amountMinor").value(8_500))
            .andExpect(jsonPath("$.createdAt").isNotEmpty());

        MvcResult cancelled = mockMvc.perform(publicCancel(fixture.token, idempotencyKey, mealUsageId, publicRequestKey))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.mealUsageId").value(mealUsageId.toString()))
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andReturn();
        assertThat(jdbcTemplate.queryForObject("select status from meal_usages where id = ?", String.class, mealUsageId))
            .isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject("select cancellation_reason from meal_usages where id = ?", String.class, mealUsageId))
            .isEqualTo("PUBLIC_SELF_CORRECTION");
        assertThat(jdbcTemplate.queryForObject(
            "select prepaid_balance from meal_contracts where id = ?", Long.class, fixture.contract.id().value()
        )).isEqualTo(10_000);

        mockMvc.perform(publicStatus(fixture.token, idempotencyKey, mealUsageId, publicRequestKey))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));
        MvcResult replay = mockMvc.perform(publicCancel(fixture.token, idempotencyKey, mealUsageId, publicRequestKey))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andReturn();
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(cancelled.getResponse().getContentAsString());
    }

    @Test
    void hidesWrongMissingTerminalAndExpiredPublicRequestCapabilities() throws Exception {
        Fixture fixture = activeFixture();
        UUID idempotencyKey = UUID.randomUUID();
        String publicRequestKey = MealUsageQrToken.generate();
        MvcResult created = mockMvc.perform(publicCreate(
            fixture.token, idempotencyKey, fixture.contract.id().value(), 8_500, publicRequestKey
        )).andExpect(status().isCreated()).andReturn();
        UUID mealUsageId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("mealUsageId").asText());
        List<String> hiddenProblems = new ArrayList<>();

        hiddenProblems.add(mockMvc.perform(publicStatus(
            fixture.token, idempotencyKey, mealUsageId, MealUsageQrToken.generate()
        )).andExpect(publicProblem(HttpStatus.NOT_FOUND.value(), "PUBLIC_MEAL_USAGE_QR_NOT_FOUND", fixture.token, publicRequestKey)).andReturn()
            .getResponse().getContentAsString());
        hiddenProblems.add(mockMvc.perform(get(path(fixture.token) + "/meal-usages/" + mealUsageId)
                .header("Idempotency-Key", idempotencyKey))
            .andExpect(publicProblem(HttpStatus.NOT_FOUND.value(), "PUBLIC_MEAL_USAGE_QR_NOT_FOUND", fixture.token, publicRequestKey)).andReturn()
            .getResponse().getContentAsString());
        hiddenProblems.add(mockMvc.perform(get(path(fixture.token) + "/meal-usages/" + mealUsageId)
                .header("Idempotency-Key", "not-a-uuid")
                .header("Public-Request-Key", publicRequestKey))
            .andExpect(publicProblem(HttpStatus.NOT_FOUND.value(), "PUBLIC_MEAL_USAGE_QR_NOT_FOUND", fixture.token, publicRequestKey)).andReturn()
            .getResponse().getContentAsString());
        hiddenProblems.add(mockMvc.perform(publicCancel(
            fixture.token, idempotencyKey, UUID.randomUUID(), publicRequestKey
        )).andExpect(publicProblem(HttpStatus.NOT_FOUND.value(), "PUBLIC_MEAL_USAGE_QR_NOT_FOUND", fixture.token, publicRequestKey)).andReturn()
            .getResponse().getContentAsString());

        jdbcTemplate.update(
            "update meal_usages set status = 'REJECTED', rejected_staff_login_id = ?, rejected_at = ? where id = ?",
            "store-hk", Timestamp.from(Instant.now()), mealUsageId
        );
        hiddenProblems.add(mockMvc.perform(publicCancel(fixture.token, idempotencyKey, mealUsageId, publicRequestKey))
            .andExpect(publicProblem(HttpStatus.NOT_FOUND.value(), "PUBLIC_MEAL_USAGE_QR_NOT_FOUND", fixture.token, publicRequestKey)).andReturn()
            .getResponse().getContentAsString());

        jdbcTemplate.update(
            "update public_meal_usage_idempotency_keys set created_at = ? where qr_context_id = ? and idempotency_key = ?",
            Timestamp.from(Instant.now().minusSeconds(601)), fixture.qrContextId, idempotencyKey
        );
        hiddenProblems.add(mockMvc.perform(publicStatus(fixture.token, idempotencyKey, mealUsageId, publicRequestKey))
            .andExpect(publicProblem(HttpStatus.NOT_FOUND.value(), "PUBLIC_MEAL_USAGE_QR_NOT_FOUND", fixture.token, publicRequestKey)).andReturn()
            .getResponse().getContentAsString());

        assertThat(Set.copyOf(hiddenProblems)).hasSize(1);
    }

    @Test
    void commitsExactlyOneTerminalTransitionWhenPublicCancellationAndStoreConfirmationRace() throws Exception {
        Fixture fixture = activeFixture();
        UUID idempotencyKey = UUID.randomUUID();
        String publicRequestKey = MealUsageQrToken.generate();
        MvcResult created = mockMvc.perform(publicCreate(
            fixture.token, idempotencyKey, fixture.contract.id().value(), 8_500, publicRequestKey
        )).andExpect(status().isCreated()).andReturn();
        MealUsageId mealUsageId = new MealUsageId(UUID.fromString(
            objectMapper.readTree(created.getResponse().getContentAsString()).get("mealUsageId").asText()
        ));
        PublicMealUsageRequestUseCase.RequestCommand cancelCommand = new PublicMealUsageRequestUseCase.RequestCommand(
            fixture.token, idempotencyKey, mealUsageId, publicRequestKey
        );
        ConfirmMealUsageCommand confirmationCommand = new ConfirmMealUsageCommand(mealUsageId, STORE_ID, "HK");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Throwable> cancellation = executor.submit(() -> runAtStart(ready, start, () -> {
                publicMealUsageRequestUseCase.cancel(cancelCommand);
                return null;
            }));
            Future<Throwable> confirmation = executor.submit(() -> runAtStart(ready, start, () -> {
                confirmMealUsageUseCase.confirm(confirmationCommand);
                return null;
            }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Throwable cancellationFailure = cancellation.get(10, TimeUnit.SECONDS);
            Throwable confirmationFailure = confirmation.get(10, TimeUnit.SECONDS);
            String finalStatus = jdbcTemplate.queryForObject(
                "select status from meal_usages where id = ?", String.class, mealUsageId.value()
            );
            assertThat(finalStatus).isIn("CANCELLED", "CONFIRMED");
            if ("CANCELLED".equals(finalStatus)) {
                assertThat(cancellationFailure).isNull();
                assertThat(confirmationFailure).isInstanceOf(MealUsageNotPendingException.class);
                assertThat(jdbcTemplate.queryForObject(
                    "select prepaid_balance from meal_contracts where id = ?", Long.class, fixture.contract.id().value()
                )).isEqualTo(10_000);
            } else {
                assertThat(cancellationFailure).isInstanceOf(PublicMealUsageQrNotFoundException.class);
                assertThat(confirmationFailure).isNull();
                assertThat(jdbcTemplate.queryForObject(
                    "select prepaid_balance from meal_contracts where id = ?", Long.class, fixture.contract.id().value()
                )).isEqualTo(1_500);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsInvalidOrUnscopedPublicPayloadsWithoutCreatingUsage() throws Exception {
        Fixture fixture = activeFixture();
        for (String body : List.of(
            "{}",
            "{\"mealContractId\":null,\"amountMinor\":12000}",
            "{\"mealContractId\":\"not-a-uuid\",\"amountMinor\":12000}",
            "{\"mealContractId\":\"" + fixture.contract.id().value() + "\",\"amountMinor\":0}",
            "{\"mealContractId\":\"" + fixture.contract.id().value() + "\",\"amountMinor\":-1}",
            "{\"mealContractId\":\"" + fixture.contract.id().value() + "\",\"amountMinor\":1000001}",
            "{\"mealContractId\":\"" + fixture.contract.id().value() + "\",\"amountMinor\":12.5}",
            "{\"mealContractId\":\"" + fixture.contract.id().value() + "\",\"amountMinor\":12000,\"storeId\":\"" + OTHER_STORE_ID.value() + "\"}"
        )) {
            mockMvc.perform(publicCreate(fixture.token, UUID.randomUUID(), body))
                .andExpect(publicProblem(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED", fixture.token));
        }
        mockMvc.perform(post(path(fixture.token) + "/meal-usages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mealContractId\":\"" + fixture.contract.id().value() + "\",\"amountMinor\":12000}"))
            .andExpect(publicProblem(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED", fixture.token));
        mockMvc.perform(post(path(fixture.token) + "/meal-usages")
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mealContractId\":\"" + fixture.contract.id().value() + "\",\"amountMinor\":12000}"))
            .andExpect(publicProblem(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED", fixture.token));
        mockMvc.perform(post(path(fixture.token) + "/meal-usages")
                .header("Idempotency-Key", "not-a-uuid")
                .header("Public-Request-Key", PUBLIC_REQUEST_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mealContractId\":\"" + fixture.contract.id().value() + "\",\"amountMinor\":12000}"))
            .andExpect(publicProblem(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED", fixture.token));
        mockMvc.perform(post(path(fixture.token) + "/meal-usages")
                .header("Idempotency-Key", UUID.randomUUID())
                .header("Public-Request-Key", PUBLIC_REQUEST_KEY)
                .contentType(MediaType.TEXT_PLAIN)
                .content("not-json"))
            .andExpect(publicProblem(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(), "UNSUPPORTED_MEDIA_TYPE", fixture.token));

        PartnerOrganization crossStorePartner = partner("다른 매장");
        MealContract crossStoreContract = mealContractRepository.save(new MealContract(
            new MealContractId(UUID.randomUUID()),
            OTHER_STORE_ID,
            MealContractPaymentType.POSTPAID,
            0,
            crossStorePartner.id(),
            true
        ));
        mockMvc.perform(publicCreate(fixture.token, UUID.randomUUID(), crossStoreContract.id().value(), 12_000))
            .andExpect(publicProblem(HttpStatus.NOT_FOUND.value(), "PUBLIC_MEAL_USAGE_QR_NOT_FOUND", fixture.token));
        assertThat(jdbcTemplate.queryForObject("select count(*) from meal_usages", Long.class)).isZero();
    }

    @Test
    void limitsDistinctSuccessfulPublicCreatesPerQrContextPerRollingMinute() throws Exception {
        Fixture fixture = activeFixture();

        for (int index = 0; index < 10; index++) {
            mockMvc.perform(publicCreate(fixture.token, UUID.randomUUID(), fixture.contract.id().value(), 1_000 + index))
                .andExpect(status().isCreated());
        }
        mockMvc.perform(publicCreate(fixture.token, UUID.randomUUID(), fixture.contract.id().value(), 20_000))
            .andExpect(publicProblem(HttpStatus.TOO_MANY_REQUESTS.value(), "PUBLIC_QR_RATE_LIMITED", fixture.token));
        assertThat(jdbcTemplate.queryForObject("select count(*) from meal_usages", Long.class)).isEqualTo(10);
    }

    @Test
    void serializesConcurrentRequestsWithTheSameQrContextAndIdempotencyKey() throws Exception {
        Fixture fixture = activeFixture();
        UUID idempotencyKey = UUID.randomUUID();

        List<MvcResult> results = performConcurrently(2, ignored -> publicCreate(
            fixture.token, idempotencyKey, fixture.contract.id().value(), 8_500
        ));

        assertThat(results.stream().map(result -> result.getResponse().getStatus()).toList()).containsOnly(HttpStatus.CREATED.value());
        List<String> representations = new ArrayList<>();
        for (MvcResult result : results) {
            representations.add(result.getResponse().getContentAsString());
        }
        assertThat(Set.copyOf(representations)).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from meal_usages where public_qr_context_id = ?", Long.class, fixture.qrContextId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from public_meal_usage_idempotency_keys where qr_context_id = ? and idempotency_key = ?",
            Long.class,
            fixture.qrContextId,
            idempotencyKey
        )).isEqualTo(1);
    }

    @Test
    void serializesElevenConcurrentDistinctKeysToTenCreatesAndOneRateLimit() throws Exception {
        Fixture fixture = activeFixture();
        List<UUID> idempotencyKeys = new ArrayList<>();
        for (int index = 0; index < 11; index++) {
            idempotencyKeys.add(UUID.randomUUID());
        }

        List<MvcResult> results = performConcurrently(11, index -> publicCreate(
            fixture.token, idempotencyKeys.get(index), fixture.contract.id().value(), 1_000 + index
        ));

        List<Integer> statuses = results.stream().map(result -> result.getResponse().getStatus()).toList();
        assertThat(statuses).containsOnly(HttpStatus.CREATED.value(), HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(statuses.stream().filter(status -> status == HttpStatus.CREATED.value()).count()).isEqualTo(10);
        List<MvcResult> limited = results.stream()
            .filter(result -> result.getResponse().getStatus() == HttpStatus.TOO_MANY_REQUESTS.value())
            .toList();
        assertThat(limited).hasSize(1);
        publicProblem(HttpStatus.TOO_MANY_REQUESTS.value(), "PUBLIC_QR_RATE_LIMITED", fixture.token).match(limited.get(0));
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from meal_usages where public_qr_context_id = ?", Long.class, fixture.qrContextId
        )).isEqualTo(10);
    }

    @Test
    void databaseAllowsLegacyContractsButEnforcesOneQrSelectableContractPerStoreAndPartner() {
        Fixture fixture = activeFixture();
        PartnerOrganizationId partnerId = fixture.contract.partnerOrganizationId().orElseThrow();
        MealContract legacy = new MealContract(
            new MealContractId(UUID.randomUUID()),
            STORE_ID,
            MealContractPaymentType.POSTPAID,
            0
        );
        mealContractRepository.save(legacy);

        assertThatThrownBy(() -> mealContractRepository.save(new MealContract(
            new MealContractId(UUID.randomUUID()),
            STORE_ID,
            MealContractPaymentType.POSTPAID,
            0,
            partnerId,
            true
        ))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void documentsTheExactUnauthenticatedPublicQrContracts() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/v1/public/meal-usage-qr/{token}'].get").exists())
            .andExpect(jsonPath("$.paths['/api/v1/public/meal-usage-qr/{token}'].get.security").doesNotExist())
            .andExpect(jsonPath("$.paths['/api/v1/public/meal-usage-qr/{token}/meal-usages'].post").exists())
            .andExpect(jsonPath("$.paths['/api/v1/public/meal-usage-qr/{token}/meal-usages'].post.security").doesNotExist())
            .andExpect(jsonPath("$.paths['/api/v1/public/meal-usage-qr/{token}/meal-usages'].post.parameters[?(@.name == 'Idempotency-Key')].required").value(true))
            .andExpect(jsonPath("$.paths['/api/v1/public/meal-usage-qr/{token}/meal-usages'].post.parameters[?(@.name == 'Public-Request-Key')].required").value(true))
            .andExpect(jsonPath("$.paths['/api/v1/public/meal-usage-qr/{token}/meal-usages'].post.responses['201']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/public/meal-usage-qr/{token}/meal-usages'].post.responses['400']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/public/meal-usage-qr/{token}/meal-usages'].post.responses['404']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/public/meal-usage-qr/{token}/meal-usages'].post.responses['409']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/public/meal-usage-qr/{token}/meal-usages'].post.responses['429']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/public/meal-usage-qr/{token}/meal-usages/{mealUsageId}'].get").exists())
            .andExpect(jsonPath("$.paths['/api/v1/public/meal-usage-qr/{token}/meal-usages/{mealUsageId}'].get.security").doesNotExist())
            .andExpect(jsonPath("$.paths['/api/v1/public/meal-usage-qr/{token}/meal-usages/{mealUsageId}/cancellations'].post").exists())
            .andExpect(jsonPath("$.paths['/api/v1/public/meal-usage-qr/{token}/meal-usages/{mealUsageId}/cancellations'].post.security").doesNotExist())
            .andReturn();

        JsonNode document = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(fieldNames(document.at("/components/schemas/PublicQrContextResponse/properties")))
            .containsExactlyInAnyOrder("storeDisplayName", "partners", "qrExpiresAt");
        assertThat(fieldNames(document.at("/components/schemas/PartnerResponse/properties")))
            .containsExactlyInAnyOrder("mealContractId", "partnerDisplayName");
        assertThat(fieldNames(document.at("/components/schemas/PublicCreationRequest/properties")))
            .containsExactlyInAnyOrder("mealContractId", "customerName", "amountMinor");
        assertThat(fieldNames(document.at("/components/schemas/PublicCreationResponse/properties")))
            .containsExactlyInAnyOrder("mealUsageId", "status", "amountMinor", "createdAt");
        assertThat(fieldNames(document.at("/components/schemas/PublicRequestResponse/properties")))
            .containsExactlyInAnyOrder("mealUsageId", "status", "amountMinor", "createdAt");
    }

    private Fixture activeFixture() {
        PartnerOrganization partner = partner("협력사 A");
        MealContract contract = mealContractRepository.save(new MealContract(
            new MealContractId(UUID.randomUUID()),
            STORE_ID,
            MealContractPaymentType.PREPAID_WITH_RECEIVABLE_OVERFLOW,
            10_000,
            partner.id(),
            true
        ));
        String token = MealUsageQrToken.generate();
        UUID qrContextId = seedQrContext(token, STORE_ID, Instant.now().plusSeconds(3_600), null);
        return new Fixture(token, qrContextId, contract);
    }

    private PartnerOrganization partner(String displayName) {
        return partnerOrganizationRepository.save(new PartnerOrganization(new PartnerOrganizationId(UUID.randomUUID()), displayName));
    }

    private UUID seedQrContext(String token, StoreId storeId, Instant expiresAt, Instant revokedAt) {
        UUID qrContextId = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into meal_usage_qr_contexts
                    (id, store_id, store_display_name, token_hash, expires_at, revoked_at, created_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """,
            qrContextId,
            storeId.value(),
            "강남점",
            MealUsageQrToken.sha256Hash(token),
            Timestamp.from(expiresAt),
            revokedAt == null ? null : Timestamp.from(revokedAt),
            Timestamp.from(Instant.now().minusSeconds(120))
        );
        return qrContextId;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder publicCreate(
        String token,
        UUID idempotencyKey,
        UUID mealContractId,
        long amountMinor
    ) {
        return publicCreate(token, idempotencyKey, mealContractId, CUSTOMER_NAME, amountMinor, PUBLIC_REQUEST_KEY);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder publicCreate(
        String token,
        UUID idempotencyKey,
        UUID mealContractId,
        long amountMinor,
        String publicRequestKey
    ) {
        return publicCreate(token, idempotencyKey, mealContractId, CUSTOMER_NAME, amountMinor, publicRequestKey);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder publicCreate(
        String token,
        UUID idempotencyKey,
        UUID mealContractId,
        String customerName,
        long amountMinor,
        String publicRequestKey
    ) {
        return publicCreate(
            token,
            idempotencyKey,
            "{\"mealContractId\":\"" + mealContractId + "\",\"customerName\":\"" + customerName + "\",\"amountMinor\":" + amountMinor + "}",
            publicRequestKey
        );
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder publicCreate(
        String token,
        UUID idempotencyKey,
        String body
    ) {
        return publicCreate(token, idempotencyKey, body, PUBLIC_REQUEST_KEY);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder publicCreate(
        String token,
        UUID idempotencyKey,
        String body,
        String publicRequestKey
    ) {
        return post(path(token) + "/meal-usages")
            .header("Idempotency-Key", idempotencyKey)
            .header("Public-Request-Key", publicRequestKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder publicStatus(
        String token,
        UUID idempotencyKey,
        UUID mealUsageId,
        String publicRequestKey
    ) {
        return get(path(token) + "/meal-usages/" + mealUsageId)
            .header("Idempotency-Key", idempotencyKey)
            .header("Public-Request-Key", publicRequestKey);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder publicCancel(
        String token,
        UUID idempotencyKey,
        UUID mealUsageId,
        String publicRequestKey
    ) {
        return post(path(token) + "/meal-usages/" + mealUsageId + "/cancellations")
            .header("Idempotency-Key", idempotencyKey)
            .header("Public-Request-Key", publicRequestKey);
    }

    private List<MvcResult> performConcurrently(
        int requestCount,
        IntFunction<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> requestFactory
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (int index = 0; index < requestCount; index++) {
                int requestIndex = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent public QR requests did not receive the start signal");
                    }
                    return mockMvc.perform(requestFactory.apply(requestIndex)).andReturn();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private Throwable runAtStart(CountDownLatch ready, CountDownLatch start, java.util.concurrent.Callable<Void> operation) {
        try {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent public request transition did not receive the start signal");
            }
            operation.call();
            return null;
        } catch (Throwable throwable) {
            return throwable;
        }
    }

    private String path(String token) {
        return "/api/v1/public/meal-usage-qr/" + token;
    }

    private org.springframework.test.web.servlet.ResultMatcher problem(int expectedStatus, String errorCode) {
        return result -> {
            status().is(expectedStatus).match(result);
            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON).match(result);
            jsonPath("$.errorCode").value(errorCode).match(result);
        };
    }

    private org.springframework.test.web.servlet.ResultMatcher publicProblem(
        int expectedStatus,
        String errorCode,
        String rawToken,
        String... rawSecrets
    ) {
        return result -> {
            problem(expectedStatus, errorCode).match(result);
            header().string(HttpHeaders.CACHE_CONTROL, "no-store").match(result);
            jsonPath("$.instance").value("/api/v1/public/meal-usage-qr").match(result);
            String responseBody = result.getResponse().getContentAsString();
            assertThat(responseBody).doesNotContain(rawToken);
            for (String rawSecret : rawSecrets) {
                assertThat(responseBody).doesNotContain(rawSecret);
            }
        };
    }

    private Set<String> fieldNames(JsonNode node) {
        return Set.copyOf(node.propertyNames());
    }

    private record Fixture(String token, UUID qrContextId, MealContract contract) {
    }
}
