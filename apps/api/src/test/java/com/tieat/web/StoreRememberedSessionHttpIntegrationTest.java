package com.tieat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tieat.TieatApiApplication;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = TieatApiApplication.class)
@AutoConfigureMockMvc
@Import(StoreRememberedSessionHttpIntegrationTest.TestClockConfiguration.class)
@Testcontainers
class StoreRememberedSessionHttpIntegrationTest {

    private static final String COOKIE_NAME = "TIEAT_SESSION";
    private static final String REMEMBERED = "tieat.auth.remembered";
    private static final String ABSOLUTE_EXPIRES_AT = "tieat.auth.absoluteExpiresAt";
    private static final String STRONG_AUTHENTICATED_AT = "tieat.auth.strongAuthenticatedAt";
    private static final String PASSWORD = "correct-password";
    private static final UUID STORE_ID = UUID.fromString("9d5e37dd-dbe2-40dc-97fb-8e77c89aa4cb");
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
    private PasswordEncoder passwordEncoder;

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
        jdbcTemplate.execute("""
            truncate table spring_session_attributes, spring_session,
                stores, store_catalog_entries, store_accounts, partner_organizations, meal_contracts
                restart identity cascade
            """);
    }

    @Test
    void ordinaryLoginUsesSessionCookieAndThirtyMinuteIdle() throws Exception {
        seedAccount("ordinary-store");
        SessionHandle initial = csrfSession(false);

        SessionHandle authenticated = login(initial, "ordinary-store", false, false);

        assertThat(authenticated.sessionId()).isNotEqualTo(initial.sessionId());
        assertThat(authenticated.cookieHeader()).doesNotContain("Max-Age=").doesNotContain("Expires=");
        assertThat(maxInactiveInterval(authenticated.sessionId())).isEqualTo(1_800);
        assertThat(sessionAttribute(authenticated.sessionId(), REMEMBERED)).isEqualTo(false);
        assertThat(sessionAttribute(authenticated.sessionId(), ABSOLUTE_EXPIRES_AT)).isNull();
        assertThat(sessionAttribute(authenticated.sessionId(), STRONG_AUTHENTICATED_AT)).isEqualTo(BASE_TIME.toEpochMilli());
    }

    @Test
    void rememberedLoginSetsCookieIdleAbsoluteAndStrongAuthenticationAttributes() throws Exception {
        seedAccount("remembered-store");
        SessionHandle initial = csrfSession(true);

        SessionHandle authenticated = login(initial, "remembered-store", true, true);

        assertThat(authenticated.sessionId()).isNotEqualTo(initial.sessionId());
        assertThat(authenticated.cookieHeader())
            .contains("Max-Age=2592000")
            .contains("Path=/")
            .contains("HttpOnly")
            .contains("SameSite=Lax")
            .contains("; Secure");
        assertThat(maxInactiveInterval(authenticated.sessionId())).isEqualTo(604_800);
        assertThat(sessionAttribute(authenticated.sessionId(), REMEMBERED)).isEqualTo(true);
        assertThat(sessionAttribute(authenticated.sessionId(), ABSOLUTE_EXPIRES_AT))
            .isEqualTo(BASE_TIME.plus(Duration.ofDays(30)).toEpochMilli());
        assertThat(sessionAttribute(authenticated.sessionId(), STRONG_AUTHENTICATED_AT)).isEqualTo(BASE_TIME.toEpochMilli());
    }

    @Test
    void signupSetsStrongAuthenticationAndOrdinarySessionPolicy() throws Exception {
        SessionHandle initial = csrfSession(false);
        MvcResult signup = mockMvc.perform(post("/api/v1/store-signups")
                .queryParam("rememberLogin", "true")
                .cookie(initial.cookie())
                .header("X-CSRF-TOKEN", initial.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"inviteCode\":\"" + StoreOnboardingHttpIntegrationSupport.INVITE_CODE
                    + "\",\"loginId\":\"remember-signup\",\"password\":\"" + PASSWORD
                    + "\",\"manualStoreName\":\"기억 가입 가게\"}"))
            .andExpect(status().isCreated())
            .andReturn();
        SessionHandle authenticated = responseSession(signup);

        assertThat(authenticated.sessionId()).isNotEqualTo(initial.sessionId());
        assertThat(authenticated.cookieHeader()).doesNotContain("Max-Age=").doesNotContain("Expires=");
        assertThat(maxInactiveInterval(authenticated.sessionId())).isEqualTo(1_800);
        assertThat(sessionAttribute(authenticated.sessionId(), REMEMBERED)).isEqualTo(false);
        assertThat(sessionAttribute(authenticated.sessionId(), ABSOLUTE_EXPIRES_AT)).isNull();
        assertThat(sessionAttribute(authenticated.sessionId(), STRONG_AUTHENTICATED_AT)).isEqualTo(BASE_TIME.toEpochMilli());
    }

    @Test
    void protectedReadsDoNotSlideRememberedAbsoluteOrStrongTimestamps() throws Exception {
        seedAccount("stable-remembered");
        SessionHandle authenticated = login(csrfSession(false), "stable-remembered", true, false);
        long absoluteExpiresAt = (Long) sessionAttribute(authenticated.sessionId(), ABSOLUTE_EXPIRES_AT);
        long strongAuthenticatedAt = (Long) sessionAttribute(authenticated.sessionId(), STRONG_AUTHENTICATED_AT);

        clock.advance(Duration.ofDays(5));
        mockMvc.perform(get("/api/v1/store-onboarding").cookie(authenticated.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.onboardingStatus").value("COMPLETE"));

        assertThat(sessionAttribute(authenticated.sessionId(), ABSOLUTE_EXPIRES_AT)).isEqualTo(absoluteExpiresAt);
        assertThat(sessionAttribute(authenticated.sessionId(), STRONG_AUTHENTICATED_AT)).isEqualTo(strongAuthenticatedAt);
        assertThat(maxInactiveInterval(authenticated.sessionId())).isEqualTo(604_800);
    }

    @Test
    void exactAbsoluteBoundaryInvalidatesRememberedSessionAndDeletesOldCookie() throws Exception {
        seedAccount("expires-remembered");
        SessionHandle authenticated = login(csrfSession(true), "expires-remembered", true, true);
        String oldCookieValue = authenticated.cookie().getValue();

        clock.advance(Duration.ofDays(30));
        MvcResult expired = mockMvc.perform(get("/api/v1/store-onboarding").secure(true).cookie(authenticated.cookie()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"))
            .andReturn();

        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from spring_session where session_id = ?", Long.class, authenticated.sessionId()
        )).isZero();
        String anonymousCookieHeader = expired.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
            .filter(value -> value.startsWith(COOKIE_NAME + "="))
            .filter(value -> !value.contains("Max-Age=0"))
            .findFirst()
            .orElseThrow();
        String anonymousCookieValue = anonymousCookieHeader.substring(
            COOKIE_NAME.length() + 1, anonymousCookieHeader.indexOf(';')
        );
        String anonymousSessionId = decodeSessionId(anonymousCookieValue);

        assertThat(anonymousCookieValue).isNotEqualTo(oldCookieValue);
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from spring_session where session_id = ?", Long.class, anonymousSessionId
        )).isEqualTo(1L);
        assertThat(sessionAttribute(anonymousSessionId, "SPRING_SECURITY_CONTEXT")).isNull();
        assertThat(sessionAttribute(anonymousSessionId, REMEMBERED)).isNull();
        assertThat(sessionAttribute(anonymousSessionId, ABSOLUTE_EXPIRES_AT)).isNull();
        assertThat(sessionAttribute(anonymousSessionId, STRONG_AUTHENTICATED_AT)).isNull();
        assertThat(anonymousCookieHeader)
            .doesNotContain("Max-Age=")
            .doesNotContain("Expires=")
            .contains("Path=/")
            .contains("; Secure")
            .contains("HttpOnly")
            .contains("SameSite=Lax");

        mockMvc.perform(get("/api/v1/store-onboarding").secure(true).cookie(authenticated.cookie()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(get("/api/v1/store-onboarding").secure(true)
                .cookie(new Cookie(COOKIE_NAME, anonymousCookieValue)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
    }

    private SessionHandle login(SessionHandle initial, String loginId, boolean remembered, boolean secure) throws Exception {
        var request = post("/api/v1/sessions")
            .secure(secure)
            .cookie(initial.cookie())
            .header("X-CSRF-TOKEN", initial.csrfToken())
            .param("loginId", loginId)
            .param("password", PASSWORD);
        if (remembered) {
            request.param("rememberLogin", "true");
        }
        MvcResult result = mockMvc.perform(request)
            .andExpect(status().isNoContent())
            .andReturn();
        return responseSession(result);
    }

    private SessionHandle csrfSession(boolean secure) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/csrf").secure(secure))
            .andExpect(status().isOk())
            .andReturn();
        Cookie cookie = responseCookie(result);
        return new SessionHandle(cookie, decodeSessionId(cookie.getValue()), csrfToken(result), cookieHeader(result));
    }

    private SessionHandle responseSession(MvcResult result) throws Exception {
        Cookie cookie = responseCookie(result);
        return new SessionHandle(cookie, decodeSessionId(cookie.getValue()), csrfToken(cookie), cookieHeader(result));
    }

    private String csrfToken(Cookie cookie) throws Exception {
        return csrfToken(mockMvc.perform(get("/api/v1/csrf").cookie(cookie))
            .andExpect(status().isOk())
            .andReturn());
    }

    private String csrfToken(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private void seedAccount(String loginId) {
        jdbcTemplate.update(
            "insert into store_accounts (login_id, password_hash, store_id, enabled) values (?, ?, ?, true)",
            loginId, passwordEncoder.encode(PASSWORD), STORE_ID
        );
    }

    private int maxInactiveInterval(String sessionId) {
        return jdbcTemplate.queryForObject(
            "select max_inactive_interval from spring_session where session_id = ?", Integer.class, sessionId
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
            (resultSet, rowNum) -> resultSet.getBytes(1),
            sessionId,
            name
        );
        if (values.isEmpty()) {
            return null;
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(values.get(0)))) {
            return input.readObject();
        }
    }

    private Cookie responseCookie(MvcResult result) {
        String setCookie = cookieHeader(result);
        String value = setCookie.substring(COOKIE_NAME.length() + 1, setCookie.indexOf(';'));
        return new Cookie(COOKIE_NAME, value);
    }

    private String cookieHeader(MvcResult result) {
        return result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
            .filter(value -> value.startsWith(COOKIE_NAME + "="))
            .findFirst()
            .orElseThrow();
    }

    private String decodeSessionId(String cookieValue) {
        return new String(Base64.getDecoder().decode(cookieValue), StandardCharsets.UTF_8);
    }

    private record SessionHandle(Cookie cookie, String sessionId, String csrfToken, String cookieHeader) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfiguration {

        @Bean
        @Primary
        MutableClock mutableTestClock() {
            return new MutableClock(BASE_TIME);
        }
    }

    static final class MutableClock extends Clock {

        private Instant current;

        MutableClock(Instant current) {
            this.current = current;
        }

        void set(Instant current) {
            this.current = current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
