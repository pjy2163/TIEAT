package com.tieat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tieat.TieatApiApplication;
import com.tieat.config.RememberedSessionPolicy;
import com.tieat.identity.adapter.out.persistence.JdbcSessionAuthenticationStateStore;
import com.tieat.store.domain.StoreId;
import com.tieat.web.StoreOnboardingHttpIntegrationSupport.SessionHandle;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = TieatApiApplication.class)
@AutoConfigureMockMvc
@Import(StorePartnerArchiveStepUpHttpIntegrationTest.TestClockConfiguration.class)
@Testcontainers
class StorePartnerArchiveStepUpHttpIntegrationTest {

    private static final String QR_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String PASSWORD = "correct-password";
    private static final String PIN = "1234";
    private static final Instant BASE_TIME = Instant.parse("2026-08-24T00:00:00Z");
    private static final String STRONG = RememberedSessionPolicy.STRONG_AUTHENTICATED_AT_ATTRIBUTE;

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
    private JdbcSessionAuthenticationStateStore stateStore;

    @Autowired
    private MutableClock clock;

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
        clock.set(BASE_TIME);
        jdbcTemplate.execute("""
            truncate table spring_session_attributes, spring_session, store_archive_pin_security,
                meal_contract_payment_term_audits, store_partner_registrations, stores,
                store_catalog_entries, store_accounts, partner_organizations, meal_contracts,
                meal_usages, meal_usage_qr_operation_audits, meal_usage_qr_contexts,
                pos_settlement_allocations, pos_settlements
            restart identity cascade
            """);
    }

    @Test
    void signupAndFreshLoginCanArchiveWithRecentPassword() throws Exception {
        ReadyStore store = readyStore("archive-stepup", "Archive step-up store", "First archive partner");
        configurePin(store);
        archive(store.session, store.csrfToken, store.contractId)
            .andExpect(status().isNoContent())
            .andExpect(result -> assertNoStore(result));
        assertArchived(store.contractId);

        UUID secondContract = createPartner(store, "Second archive partner");
        SessionHandle freshLogin = StoreOnboardingHttpIntegrationSupport.authenticatedSession(
            mockMvc, objectMapper, store.loginId, PASSWORD
        );
        String freshCsrf = StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, freshLogin);
        archive(freshLogin, freshCsrf, secondContract)
            .andExpect(status().isNoContent())
            .andExpect(result -> assertNoStore(result));
        assertArchived(secondContract);
    }

    @Test
    void invalidStrongStatesHaveTheSameRequiredProblem() throws Exception {
        ReadyStore store = readyStore("archive-invalid-state", "Invalid state store", "Invalid state partner");
        configurePin(store);
        long now = clock.instant().toEpochMilli();
        List<Object> states = List.of(
            "wrong-type",
            now + 1,
            now - RememberedSessionPolicy.STRONG_AUTHENTICATION_WINDOW.toMillis() - 1,
            now - RememberedSessionPolicy.STRONG_AUTHENTICATION_WINDOW.toMillis(),
            Long.MAX_VALUE
        );
        setStrong(store.session, null);
        MvcResult baseline = archive(store.session, store.csrfToken, store.contractId).andReturn();
        assertRequiredProblem(baseline, store.contractId);
        for (Object state : states) {
            setStrong(store.session, state);
            MvcResult result = archive(store.session, store.csrfToken, store.contractId).andReturn();
            assertRequiredProblem(result, store.contractId);
            assertSameProblem(baseline, result);
        }
        assertThat(failedAttempts(store.storeId)).isZero();
        assertNotArchived(store.contractId);
    }

    @Test
    void passwordAuthenticationAtTenMinutesMinusOneMillisecondAllowsArchive() throws Exception {
        ReadyStore store = readyStore("archive-boundary", "Boundary store", "Boundary partner");
        configurePin(store);
        setStrong(store.session, clock.instant().minus(RememberedSessionPolicy.STRONG_AUTHENTICATION_WINDOW).plusMillis(1).toEpochMilli());

        archive(store.session, store.csrfToken, store.contractId)
            .andExpect(status().isNoContent())
            .andExpect(result -> assertNoStore(result));
        assertArchived(store.contractId);
    }

    @Test
    void staleGateDoesNotCountPinFailureButFreshWrongPinDoes() throws Exception {
        ReadyStore store = readyStore("archive-pin-count", "PIN count store", "PIN count partner");
        configurePin(store);
        setStrong(store.session, clock.instant().minus(RememberedSessionPolicy.STRONG_AUTHENTICATION_WINDOW).minusMillis(1).toEpochMilli());
        assertProblem(archive(store.session, store.csrfToken, store.contractId, "0000").andReturn(), 403, "PASSWORD_REAUTHENTICATION_REQUIRED");
        assertThat(failedAttempts(store.storeId)).isZero();
        assertNotArchived(store.contractId);

        setStrong(store.session, clock.instant().toEpochMilli());
        assertProblem(archive(store.session, store.csrfToken, store.contractId, "0000").andReturn(), 403, "STORE_ARCHIVE_PIN_INVALID");
        assertThat(failedAttempts(store.storeId)).isEqualTo(1);
        assertNotArchived(store.contractId);
    }

    @Test
    void protectedReadsDoNotSlideStrongAuthenticationAndExactBoundaryRejects() throws Exception {
        ReadyStore store = readyStore("archive-poll", "Poll store", "Poll partner");
        configurePin(store);
        long strong = clock.instant().toEpochMilli();
        mockMvc.perform(get("/api/v1/store-partners").cookie(store.session.cookie()))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/store-onboarding").cookie(store.session.cookie()))
            .andExpect(status().isOk());
        assertThat(strong(store.session)).isEqualTo(strong);

        setStrong(store.session, clock.instant().minus(RememberedSessionPolicy.STRONG_AUTHENTICATION_WINDOW).toEpochMilli());
        assertProblem(archive(store.session, store.csrfToken, store.contractId).andReturn(), 403, "PASSWORD_REAUTHENTICATION_REQUIRED");
        assertNotArchived(store.contractId);
    }

    @Test
    void anonymousArchiveRequiresAuthenticationEvenWithValidCsrf() throws Exception {
        ReadyStore store = readyStore("archive-anonymous", "Anonymous store", "Anonymous partner");
        configurePin(store);
        SessionHandle anonymous = StoreOnboardingHttpIntegrationSupport.csrfSession(mockMvc, objectMapper);

        assertProblem(archive(anonymous, anonymous.csrfToken(), store.contractId).andReturn(), 401, "AUTHENTICATION_REQUIRED");
        assertNotArchived(store.contractId);
    }

    @Test
    void foreignContractRemainsNotFoundAndLegacyDeleteRemainsDenied() throws Exception {
        ReadyStore owner = readyStore("archive-owner-stepup", "Owner store", "Owner partner");
        ReadyStore foreign = readyStore("archive-foreign-stepup", "Foreign store", "Foreign partner");
        configurePin(owner);
        assertProblem(archive(owner.session, owner.csrfToken, foreign.contractId).andReturn(), 404, "STORE_PARTNER_NOT_FOUND");
        assertNotArchived(foreign.contractId);

        MvcResult legacy = mockMvc.perform(delete("/api/v1/store-partners/{mealContractId}", owner.contractId)
                .cookie(owner.session.cookie())
                .header("X-CSRF-TOKEN", owner.csrfToken))
            .andReturn();
        assertProblem(legacy, 403, "STORE_PARTNER_ARCHIVE_PIN_REQUIRED");
        assertNotArchived(owner.contractId);
    }

    private ReadyStore readyStore(String loginId, String storeName, String partnerName) throws Exception {
        SessionHandle session = StoreOnboardingHttpIntegrationSupport.signUpManualStore(
            mockMvc, objectMapper, loginId, PASSWORD, storeName
        );
        String csrfToken = StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, session);
        MvcResult result = mockMvc.perform(StoreOnboardingHttpIntegrationSupport.partnerRequest(
                session,
                csrfToken,
                "{\"partnerName\":\"" + partnerName + "\",\"partnerKind\":\"ORGANIZATION\",\"paymentType\":\"POSTPAID\",\"initialPrepaidBalanceMinor\":0,\"qrSelectable\":true}"
            ))
            .andExpect(status().isCreated())
            .andReturn();
        UUID storeId = jdbcTemplate.queryForObject("select store_id from store_accounts where login_id = ?", UUID.class, loginId);
        UUID contractId = UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("mealContractId").asText());
        return new ReadyStore(loginId, session, csrfToken, new StoreId(storeId), contractId);
    }

    private UUID createPartner(ReadyStore store, String partnerName) throws Exception {
        String csrfToken = StoreOnboardingHttpIntegrationSupport.csrfToken(mockMvc, objectMapper, store.session);
        MvcResult result = mockMvc.perform(post("/api/v1/store-partners")
                .cookie(store.session.cookie())
                .header("X-CSRF-TOKEN", csrfToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"partnerName\":\"" + partnerName + "\",\"partnerKind\":\"ORGANIZATION\",\"paymentType\":\"POSTPAID\",\"initialPrepaidBalanceMinor\":0,\"qrSelectable\":true}"))
            .andExpect(status().isCreated())
            .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("mealContractId").asText());
    }

    private void configurePin(ReadyStore store) throws Exception {
        mockMvc.perform(put("/api/v1/store-archive-pin")
                .cookie(store.session.cookie())
                .header("X-CSRF-TOKEN", store.csrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPin\":null,\"accountPassword\":\"" + PASSWORD
                    + "\",\"newPin\":\"" + PIN + "\",\"newPinConfirmation\":\"" + PIN + "\"}"))
            .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions archive(
        SessionHandle session, String csrfToken, UUID contractId
    ) throws Exception {
        return archive(session, csrfToken, contractId, PIN);
    }

    private org.springframework.test.web.servlet.ResultActions archive(
        SessionHandle session, String csrfToken, UUID contractId, String pin
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/store-partners/{mealContractId}/archive", contractId)
            .cookie(session.cookie())
            .header("X-CSRF-TOKEN", csrfToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"pin\":\"" + pin + "\"}"));
    }

    private void setStrong(SessionHandle session, Object value) {
        JdbcSessionAuthenticationStateStore.SessionRow row = stateStore.lock(session.sessionId()).orElseThrow();
        if (value == null) {
            stateStore.deleteAttribute(row, STRONG);
        } else {
            stateStore.upsertAttribute(row, STRONG, value);
        }
    }

    private Object strong(SessionHandle session) {
        JdbcSessionAuthenticationStateStore.SessionRow row = stateStore.lock(session.sessionId()).orElseThrow();
        return stateStore.readAttribute(row, STRONG);
    }

    private int failedAttempts(StoreId storeId) {
        return jdbcTemplate.queryForObject(
            "select failed_attempts from store_archive_pin_security where store_id = ?", Integer.class, storeId.value()
        );
    }

    private void assertArchived(UUID contractId) {
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from meal_contracts where id = ? and archived_at is not null", Long.class, contractId
        )).isEqualTo(1L);
    }

    private void assertNotArchived(UUID contractId) {
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from meal_contracts where id = ? and archived_at is null", Long.class, contractId
        )).isEqualTo(1L);
    }

    private void assertNoStore(MvcResult result) {
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    private void assertRequiredProblem(MvcResult result, UUID contractId) throws Exception {
        assertProblem(result, 403, "PASSWORD_REAUTHENTICATION_REQUIRED");
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("type").asText()).isEqualTo("urn:tieat:problem:password_reauthentication_required");
        assertThat(body.get("detail").asText()).isEqualTo("Password reauthentication is required");
        assertThat(body.get("instance").asText()).isEqualTo("/api/v1/store-partners/" + contractId + "/archive");
    }

    private void assertProblem(MvcResult result, int status, String errorCode) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).contains(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("errorCode").asText()).isEqualTo(errorCode);
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    private void assertSameProblem(MvcResult expected, MvcResult actual) throws Exception {
        JsonNode first = objectMapper.readTree(expected.getResponse().getContentAsString());
        JsonNode second = objectMapper.readTree(actual.getResponse().getContentAsString());
        for (String field : List.of("status", "errorCode", "type", "detail", "instance")) {
            assertThat(second.get(field)).isEqualTo(first.get(field));
        }
    }

    private record ReadyStore(
        String loginId,
        SessionHandle session,
        String csrfToken,
        StoreId storeId,
        UUID contractId
    ) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfiguration {
        @Bean
        @Primary
        MutableClock archiveTestClock() { return new MutableClock(BASE_TIME); }
    }

    static final class MutableClock extends Clock {
        private Instant current;
        MutableClock(Instant current) { this.current = current; }
        void set(Instant current) { this.current = current; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
}
