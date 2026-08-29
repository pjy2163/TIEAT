package com.tieat.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;

import com.tieat.TieatApiApplication;
import com.tieat.ledger.domain.Confirmation;
import com.tieat.ledger.domain.EntrySource;
import com.tieat.ledger.domain.MealUsage;
import com.tieat.ledger.domain.MealUsageId;
import com.tieat.ledger.domain.MealUsageRepository;
import com.tieat.ledger.domain.PrepaidAllocation;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.store.domain.StoreId;
import com.tieat.web.StoreOnboardingHttpIntegrationSupport.SessionHandle;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = StorePartnerContextHttpIntegrationSupport.R032TestApplication.class)
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
    "spring.servlet.multipart.max-file-size=10MB",
    "spring.servlet.multipart.max-request-size=11MB"
})
abstract class StorePartnerContextHttpIntegrationSupport {

    private static final String QR_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
        .withDatabaseName("tieat")
        .withUsername("tieat")
        .withPassword("tieat");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    MealUsageRepository mealUsageRepository;

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
            truncate table store_archive_pin_security, meal_contract_payment_term_audits,
                store_partner_registrations, stores, store_catalog_entries, store_accounts,
                partner_organizations, meal_contracts, meal_usages, meal_usage_qr_operation_audits,
                meal_usage_qr_contexts,
                pos_settlement_allocations, pos_settlements
            restart identity cascade
            """);
    }

    ReadyStore readyStore(String loginId, String storeName, String firstPartnerName) throws Exception {
        return readyStore(loginId, storeName, firstPartnerName, "correct-password");
    }

    ReadyStore readyStore(String loginId, String storeName, String firstPartnerName, String accountPassword) throws Exception {
        SessionHandle session = StoreOnboardingHttpIntegrationSupport.signUpManualStore(
            mockMvc, objectMapper, loginId, accountPassword, storeName
        );
        String csrfToken = StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, session);
        mockMvc.perform(StoreOnboardingHttpIntegrationSupport.partnerRequest(
            session,
            csrfToken,
            "{\"partnerName\":\"" + firstPartnerName
                + "\",\"partnerKind\":\"ORGANIZATION\",\"paymentType\":\"POSTPAID\",\"initialPrepaidBalanceMinor\":0,\"qrSelectable\":true}"
        )).andExpect(status().isCreated());
        UUID storeId = jdbcTemplate.queryForObject(
            "select store_id from store_accounts where login_id = ?", UUID.class, loginId
        );
        return new ReadyStore(loginId, session, csrfToken, new StoreId(storeId));
    }

    Partner firstPartner(ReadyStore store) throws Exception {
        JsonNode items = json(mockMvc.perform(get("/api/v1/store-partners").cookie(store.session.cookie()))
            .andExpect(status().isOk()).andReturn()).body();
        JsonNode item = items.get(0);
        return new Partner(
            UUID.fromString(item.get("mealContractId").asText()),
            item.get("partnerDisplayName").asText(),
            item.get("partnerKind").asText(),
            item.get("paymentType").asText(),
            item.get("qrSelectable").asBoolean()
        );
    }

    Partner createPartner(ReadyStore store, String name, String paymentType, long initialBalance,
                          boolean qrSelectable, UUID idempotencyKey) throws Exception {
        return createPartner(store, name, paymentType, initialBalance, qrSelectable, idempotencyKey, "ORGANIZATION");
    }

    Partner createPartner(ReadyStore store, String name, String paymentType, long initialBalance,
                          boolean qrSelectable, UUID idempotencyKey, String partnerKind) throws Exception {
        MvcResult result = mockMvc.perform(storePartnerRequest(
            store.session,
            StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, store.session),
            idempotencyKey,
            partnerBody(name, paymentType, Long.toString(initialBalance), Boolean.toString(qrSelectable), partnerKind)
        )).andExpect(status().isCreated()).andReturn();
        JsonNode body = json(result).body();
        return new Partner(
            UUID.fromString(body.get("mealContractId").asText()),
            body.get("partnerDisplayName").asText(),
            body.get("partnerKind").asText(),
            body.get("paymentType").asText(),
            body.get("qrSelectable").asBoolean()
        );
    }

    List<MvcResult> runConcurrentCreates(ReadyStore store, UUID key, String firstBody, String secondBody) throws Exception {
        SessionHandle firstSession = StoreOnboardingHttpIntegrationSupport.authenticatedSession(
            mockMvc, objectMapper, store.loginId, "correct-password"
        );
        SessionHandle secondSession = StoreOnboardingHttpIntegrationSupport.authenticatedSession(
            mockMvc, objectMapper, store.loginId, "correct-password"
        );
        String firstCsrf = StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, firstSession);
        String secondCsrf = StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, secondSession);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> firstResult = executor.submit(concurrentRequest(
                firstSession, firstCsrf, key, firstBody, ready, start
            ));
            Future<MvcResult> secondResult = executor.submit(concurrentRequest(
                secondSession, secondCsrf, key, secondBody, ready, start
            ));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(firstResult.get(30, TimeUnit.SECONDS), secondResult.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private Callable<MvcResult> concurrentRequest(SessionHandle session, String csrfToken, UUID key, String body,
                                                    CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            return mockMvc.perform(storePartnerRequest(session, csrfToken, key, body)).andReturn();
        };
    }

    void saveConfirmed(StoreId storeId, UUID contractId, String partnerName, long amount, String id) {
        mealUsageRepository.save(MealUsage.restoreConfirmed(
            new MealUsageId(UUID.fromString(id)), storeId, new MealContractId(contractId), EntrySource.PARTNER_MOBILE,
            amount, Instant.parse("2026-08-15T01:00:00Z"), 0,
            new Confirmation("HK", Instant.parse("2026-08-15T02:00:00Z")),
            new PrepaidAllocation(amount, 0, amount, 0), partnerName, null
        ));
    }

    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder storePartnerRequest(
        SessionHandle session, String csrfToken, UUID idempotencyKey, String body
    ) {
        return post("/api/v1/store-partners")
            .cookie(session.cookie()).header("X-CSRF-TOKEN", csrfToken)
            .header("Idempotency-Key", idempotencyKey).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder archiveWithPinRequest(
        SessionHandle session, String csrfToken, UUID mealContractId, String pin
    ) {
        return post("/api/v1/store-partners/{mealContractId}/archive", mealContractId)
            .cookie(session.cookie()).header("X-CSRF-TOKEN", csrfToken).contentType(MediaType.APPLICATION_JSON)
            .content("{\"pin\":\"" + pin + "\"}");
    }

    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder archivePinSettingsRequest(
        ReadyStore store, String currentPin, String accountPassword, String newPin, String newPinConfirmation
    ) {
        return put("/api/v1/store-archive-pin")
            .cookie(store.session.cookie()).header("X-CSRF-TOKEN", store.csrfToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPin\":" + quotedOrNull(currentPin)
                + ",\"accountPassword\":" + quotedOrNull(accountPassword)
                + ",\"newPin\":\"" + newPin + "\",\"newPinConfirmation\":\"" + newPinConfirmation + "\"}");
    }

    private String quotedOrNull(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder paymentTermRequest(
        SessionHandle session, String csrfToken, UUID mealContractId, String expectedPaymentType,
        String paymentType, Long prepaidBalanceMinor
    ) {
        return patch("/api/v1/store-partners/{mealContractId}/payment-terms", mealContractId)
            .cookie(session.cookie()).header("X-CSRF-TOKEN", csrfToken).contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedPaymentType\":\"" + expectedPaymentType + "\",\"paymentType\":\""
                + paymentType + "\",\"prepaidBalanceMinor\":"
                + (prepaidBalanceMinor == null ? "null" : prepaidBalanceMinor) + "}");
    }

    void configureArchivePin(ReadyStore store) throws Exception {
        mockMvc.perform(archivePinSettingsRequest(store, null, "correct-password", "1234", "1234"))
            .andExpect(status().isOk());
    }

    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder mealUsageCreationRequest(
        SessionHandle session, String csrfToken, UUID mealContractId, long amountMinor
    ) {
        return post("/api/v1/meal-usages").cookie(session.cookie()).header("X-CSRF-TOKEN", csrfToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"mealContractId\":\"" + mealContractId + "\",\"amountMinor\":" + amountMinor + "}");
    }

    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder confirmMealUsageRequest(
        SessionHandle session, String csrfToken, UUID mealUsageId
    ) {
        return post("/api/v1/meal-usages/{mealUsageId}/confirmations", mealUsageId)
            .cookie(session.cookie()).header("X-CSRF-TOKEN", csrfToken).contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirmerInitials\":\"HK\"}");
    }

    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder monthlyRequest(
        SessionHandle session, String month, UUID mealContractId
    ) {
        var request = get("/api/v1/meal-usages/months/{month}", month).cookie(session.cookie())
            .param("page", "0").param("size", "20");
        if (mealContractId != null) {
            request.param("mealContractId", mealContractId.toString());
        }
        return request;
    }

    String partnerBody(String name, String paymentType, String initialBalance, String qrSelectable) {
        return partnerBody(name, paymentType, initialBalance, qrSelectable, "ORGANIZATION");
    }

    String partnerBody(String name, String paymentType, String initialBalance, String qrSelectable, String partnerKind) {
        return "{\"partnerName\":\"" + name + "\",\"paymentType\":\"" + paymentType
            + "\",\"partnerKind\":\"" + partnerKind + "\",\"initialPrepaidBalanceMinor\":"
            + initialBalance + ",\"qrSelectable\":" + qrSelectable + "}";
    }

    String partnerBodyWithContacts(String name, String paymentType, String initialBalance, String qrSelectable,
                                   String representativePhone, String representativeEmail) {
        return "{\"partnerName\":\"" + name + "\",\"paymentType\":\"" + paymentType
            + "\",\"partnerKind\":\"ORGANIZATION\",\"initialPrepaidBalanceMinor\":" + initialBalance
            + ",\"qrSelectable\":" + qrSelectable + ",\"representativePhone\":\"" + representativePhone
            + "\",\"representativeEmail\":\"" + representativeEmail + "\"}";
    }

    JsonResult json(MvcResult result) throws Exception {
        return new JsonResult(result, objectMapper.readTree(result.getResponse().getContentAsString()));
    }

    long count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
    }

    long countForStore(String table, StoreId storeId) {
        return jdbcTemplate.queryForObject("select count(*) from " + table + " where store_id = ?", Long.class, storeId.value());
    }

    static final class ReadyStore {
        final String loginId;
        final SessionHandle session;
        final String csrfToken;
        final StoreId storeId;

        ReadyStore(String loginId, SessionHandle session, String csrfToken, StoreId storeId) {
            this.loginId = loginId;
            this.session = session;
            this.csrfToken = csrfToken;
            this.storeId = storeId;
        }

        String loginId() { return loginId; }
        SessionHandle session() { return session; }
        String csrfToken() { return csrfToken; }
        StoreId storeId() { return storeId; }
    }

    static final class Partner {
        final UUID id;
        final String name;
        final String partnerKind;
        final String paymentType;
        final boolean qrSelectable;

        Partner(UUID id, String name, String partnerKind, String paymentType, boolean qrSelectable) {
            this.id = id;
            this.name = name;
            this.partnerKind = partnerKind;
            this.paymentType = paymentType;
            this.qrSelectable = qrSelectable;
        }

        UUID id() { return id; }
        String name() { return name; }
        String partnerKind() { return partnerKind; }
        String paymentType() { return paymentType; }
        boolean qrSelectable() { return qrSelectable; }
    }

    static final class JsonResult {
        final MvcResult result;
        final JsonNode body;

        JsonResult(MvcResult result, JsonNode body) {
            this.result = result;
            this.body = body;
        }

        MvcResult result() { return result; }
        JsonNode body() { return body; }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = UserDetailsServiceAutoConfiguration.class)
    @EntityScan(basePackages = "com.tieat")
    @EnableJpaRepositories(basePackages = "com.tieat")
    @ComponentScan(
        basePackages = "com.tieat",
        excludeFilters = {
            @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = TieatApiApplication.class),
            @ComponentScan.Filter(type = FilterType.CUSTOM, classes = ReceiptPackageTypeFilter.class),
            @ComponentScan.Filter(type = FilterType.CUSTOM, classes = NestedTestConfigurationFilter.class),
            @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.tieat\\.web\\.PosSettlementController"),
            @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.tieat\\.web\\.PosSettlementReceipt.*")
        }
    )
    static class R032TestApplication {
    }

    static final class ReceiptPackageTypeFilter implements TypeFilter {
        @Override
        public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory) {
            return metadataReader.getClassMetadata().getClassName().startsWith("com.tieat.settlement.receipt.");
        }
    }

    static final class NestedTestConfigurationFilter implements TypeFilter {
        @Override
        public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory) {
            String className = metadataReader.getClassMetadata().getClassName();
            int nestedSeparator = className.indexOf('$');
            if (nestedSeparator < 0) {
                return false;
            }
            String enclosingName = className.substring(0, nestedSeparator);
            String simpleEnclosingName = enclosingName.substring(enclosingName.lastIndexOf('.') + 1);
            return simpleEnclosingName.endsWith("Test") || simpleEnclosingName.endsWith("IntegrationTest");
        }
    }
}
