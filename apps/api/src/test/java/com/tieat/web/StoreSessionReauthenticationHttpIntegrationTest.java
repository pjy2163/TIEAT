package com.tieat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tieat.TieatApiApplication;
import com.tieat.config.RememberedSessionPolicy;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
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
@Import(StoreSessionReauthenticationHttpIntegrationTest.TestClockConfiguration.class)
@Testcontainers
class StoreSessionReauthenticationHttpIntegrationTest {

    private static final String COOKIE_NAME = "TIEAT_SESSION";
    private static final String PASSWORD = "correct-password";
    private static final String WRONG_PASSWORD = "wrong-password";
    private static final String STRONG = RememberedSessionPolicy.STRONG_AUTHENTICATED_AT_ATTRIBUTE;
    private static final String ATTEMPTS = RememberedSessionPolicy.REAUTH_FAILED_ATTEMPTS_ATTRIBUTE;
    private static final String ABSOLUTE = RememberedSessionPolicy.ABSOLUTE_EXPIRES_AT_ATTRIBUTE;
    private static final Instant BASE_TIME = Instant.parse("2026-08-24T00:00:00Z");

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
    private MutableClock clock;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("tieat.onboarding.invite-code", () -> StoreOnboardingHttpIntegrationSupport.INVITE_CODE);
    }

    @BeforeEach
    void clearDatabase() {
        clock.set(BASE_TIME);
        jdbcTemplate.execute("truncate table spring_session_attributes, spring_session, stores, store_accounts restart identity cascade");
    }

    @Test
    void ordinarySuccessRotatesSessionAndSetsStrongAuthentication() throws Exception {
        StoreOnboardingHttpIntegrationSupport.SessionHandle authenticated = signUp("ordinary-reauth");
        String oldSessionId = authenticated.sessionId();
        clock.advance(Duration.ofMinutes(1));

        MvcResult result = reauthenticate(authenticated, PASSWORD, false);
        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(cookieHeader(result)).doesNotContain("Max-Age=").doesNotContain("Expires=");
        StoreOnboardingHttpIntegrationSupport.SessionHandle rotated = responseSession(result);

        assertThat(rotated.sessionId()).isNotEqualTo(oldSessionId);
        assertThat(sessionCount(oldSessionId)).isZero();
        assertThat(sessionAttribute(rotated.sessionId(), STRONG)).isEqualTo(clock.instant().toEpochMilli());
        mockMvc.perform(get("/api/v1/store-onboarding").cookie(authenticated.cookie()))
            .andExpect(oldSessionResponse -> assertProblem(oldSessionResponse, 401, "AUTHENTICATION_REQUIRED"));
        mockMvc.perform(get("/api/v1/store-onboarding").cookie(rotated.cookie()))
            .andExpect(status().isOk());
    }

    @Test
    void rememberedSuccessUsesRemainingAbsoluteCookieLifetime() throws Exception {
        signUp("remembered-reauth");
        StoreOnboardingHttpIntegrationSupport.SessionHandle authenticated = login(
            StoreOnboardingHttpIntegrationSupport.csrfSession(mockMvc, objectMapper), "remembered-reauth", true, true
        );
        long absoluteExpiresAt = (Long) sessionAttribute(authenticated.sessionId(), ABSOLUTE);
        clock.advance(Duration.ofDays(5).plusMillis(1_234));

        MvcResult result = reauthenticate(authenticated, PASSWORD, true);
        long expectedRemaining = Duration.ofDays(25).minusMillis(1_234).toSeconds();
        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(cookieHeader(result))
            .contains("Max-Age=" + expectedRemaining)
            .contains("Path=/")
            .contains("Secure")
            .contains("HttpOnly")
            .contains("SameSite=Lax");
        StoreOnboardingHttpIntegrationSupport.SessionHandle rotated = responseSession(result);
        assertThat(sessionAttribute(rotated.sessionId(), ABSOLUTE)).isEqualTo(absoluteExpiresAt);
        assertThat(sessionAttribute(rotated.sessionId(), STRONG)).isEqualTo(clock.instant().toEpochMilli());
    }

    @Test
    void wrongAndLockedFailuresShareGenericContractWithoutRotation() throws Exception {
        StoreOnboardingHttpIntegrationSupport.SessionHandle authenticated = signUp("locked-reauth");
        String sessionId = authenticated.sessionId();
        Object strong = sessionAttribute(sessionId, STRONG);

        MvcResult first = reauthenticate(authenticated, WRONG_PASSWORD, false);
        assertProblem(first, 401, "SESSION_REAUTHENTICATION_FAILED");
        assertNoCookie(first);
        for (int attempt = 1; attempt < 5; attempt++) {
            assertProblem(reauthenticate(authenticated, WRONG_PASSWORD, false), 401, "SESSION_REAUTHENTICATION_FAILED");
        }
        MvcResult locked = reauthenticate(authenticated, PASSWORD, false);
        assertProblem(locked, 401, "SESSION_REAUTHENTICATION_FAILED");
        assertSameFailureShape(first, locked);
        assertNoCookie(locked);
        assertThat(sessionCount(sessionId)).isEqualTo(1L);
        assertThat(sessionAttribute(sessionId, ATTEMPTS)).isEqualTo(5);
        assertThat(sessionAttribute(sessionId, STRONG)).isEqualTo(strong);
        mockMvc.perform(get("/api/v1/store-onboarding").cookie(authenticated.cookie()))
            .andExpect(status().isOk());

        jdbcTemplate.update("update spring_session set principal_name = ? where session_id = ?", "other-login", sessionId);
        MvcResult unavailable = reauthenticate(authenticated, PASSWORD, false);
        assertProblem(unavailable, 401, "AUTHENTICATION_REQUIRED");
        assertNoCookie(unavailable);
        assertThat(sessionCount(sessionId)).isEqualTo(1L);
        assertThat(sessionAttribute(sessionId, STRONG)).isEqualTo(strong);
    }

    @Test
    void csrfAndStructuralInputFailuresDoNotCountButBlankDoes() throws Exception {
        StoreOnboardingHttpIntegrationSupport.SessionHandle authenticated = signUp("boundary-reauth");
        String sessionId = authenticated.sessionId();
        Object strong = sessionAttribute(sessionId, STRONG);

        assertProblem(mockMvc.perform(reauthenticationRequest(authenticated, "{\"password\":\"" + PASSWORD + "\"}")
                .secure(false).header("X-CSRF-TOKEN", "bad-token")).andReturn(), 403, "CSRF_TOKEN_INVALID");
        assertProblem(mockMvc.perform(reauthenticationRequest(authenticated, "{\"password\":\"" + PASSWORD + "\"}")
                .secure(false)).andReturn(), 403, "CSRF_TOKEN_INVALID");
        for (String body : List.of("{", "", "{}", "{\"password\":null}",
            "{\"password\":\"" + PASSWORD + "\",\"extra\":true}")) {
            MvcResult invalid = mockMvc.perform(reauthenticationRequest(authenticated, body)
                    .header("X-CSRF-TOKEN", authenticated.csrfToken()))
                .andReturn();
            assertProblem(invalid, 400, "SESSION_REAUTHENTICATION_INVALID");
        }
        assertThat(sessionAttribute(sessionId, ATTEMPTS)).isNull();
        assertThat(sessionAttribute(sessionId, STRONG)).isEqualTo(strong);

        MvcResult blank = reauthenticate(authenticated, "   ", false);
        assertProblem(blank, 401, "SESSION_REAUTHENTICATION_FAILED");
        assertThat(sessionAttribute(sessionId, ATTEMPTS)).isEqualTo(1);
        assertNoCookie(blank);
    }

    private StoreOnboardingHttpIntegrationSupport.SessionHandle signUp(String loginId) throws Exception {
        return StoreOnboardingHttpIntegrationSupport.signUpManualStore(
            mockMvc, objectMapper, loginId, PASSWORD, "Reauthentication " + loginId
        );
    }

    private StoreOnboardingHttpIntegrationSupport.SessionHandle login(
        StoreOnboardingHttpIntegrationSupport.SessionHandle initial,
        String loginId,
        boolean remembered,
        boolean secure
    ) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/sessions")
            .secure(secure)
            .cookie(initial.cookie())
            .header("X-CSRF-TOKEN", initial.csrfToken())
            .param("loginId", loginId)
            .param("password", PASSWORD);
        if (remembered) {
            request.param("rememberLogin", "true");
        }
        return responseSession(mockMvc.perform(request).andExpect(status().isNoContent()).andReturn());
    }

    private MvcResult reauthenticate(
        StoreOnboardingHttpIntegrationSupport.SessionHandle session,
        String password,
        boolean secure
    ) throws Exception {
        return mockMvc.perform(reauthenticationRequest(session, "{\"password\":\"" + password + "\"}").secure(secure)
                .header("X-CSRF-TOKEN", session.csrfToken()))
            .andReturn();
    }

    private MockHttpServletRequestBuilder reauthenticationRequest(
        StoreOnboardingHttpIntegrationSupport.SessionHandle session,
        String body
    ) {
        return post("/api/v1/session-reauthentications")
            .cookie(session.cookie())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    }

    private StoreOnboardingHttpIntegrationSupport.SessionHandle responseSession(MvcResult result) throws Exception {
        Cookie cookie = responseCookie(result);
        return new StoreOnboardingHttpIntegrationSupport.SessionHandle(
            cookie, decodeSessionId(cookie.getValue()), csrfToken(cookie)
        );
    }

    private String csrfToken(Cookie cookie) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/csrf").cookie(cookie)).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("token").asText();
    }

    private Cookie responseCookie(MvcResult result) {
        String header = cookieHeader(result);
        String value = header.substring(COOKIE_NAME.length() + 1, header.indexOf(';'));
        return new Cookie(COOKIE_NAME, value);
    }

    private String cookieHeader(MvcResult result) {
        return result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
            .filter(value -> value.startsWith(COOKIE_NAME + "="))
            .findFirst()
            .orElseThrow();
    }

    private void assertNoCookie(MvcResult result) {
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    private void assertProblem(MvcResult result, int status, String errorCode) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("errorCode").asText()).isEqualTo(errorCode);
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    private void assertSameFailureShape(MvcResult first, MvcResult locked) throws Exception {
        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
        JsonNode lockedBody = objectMapper.readTree(locked.getResponse().getContentAsString());
        for (String field : List.of("status", "errorCode", "detail", "type")) {
            assertThat(lockedBody.get(field)).isEqualTo(firstBody.get(field));
        }
    }

    private long sessionCount(String sessionId) {
        return jdbcTemplate.queryForObject(
            "select count(*) from spring_session where session_id = ?", Long.class, sessionId
        );
    }

    private Object sessionAttribute(String sessionId, String name) throws Exception {
        List<byte[]> values = jdbcTemplate.query(
            """
            select a.attribute_bytes
            from spring_session_attributes a
            join spring_session s on s.primary_id = a.session_primary_id
            where s.session_id = ? and a.attribute_name = ?
            """,
            (resultSet, rowNum) -> resultSet.getBytes(1), sessionId, name
        );
        if (values.isEmpty()) {
            return null;
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(values.get(0)))) {
            return input.readObject();
        }
    }

    private String decodeSessionId(String cookieValue) {
        return new String(Base64.getDecoder().decode(cookieValue), StandardCharsets.UTF_8);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfiguration {
        @Bean
        @Primary
        MutableClock reauthenticationTestClock() { return new MutableClock(BASE_TIME); }
    }

    static final class MutableClock extends Clock {
        private Instant current;
        MutableClock(Instant current) { this.current = current; }
        void set(Instant current) { this.current = current; }
        void advance(Duration duration) { current = current.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
}
