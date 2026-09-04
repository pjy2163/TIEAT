package com.tieat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tieat.TieatApiApplication;
import com.tieat.config.SessionCookieConfiguration;
import com.tieat.identity.adapter.in.security.StoreAccountPrincipal;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = TieatApiApplication.class, properties = "tieat.session.cookie.secure=")
@AutoConfigureMockMvc
@Testcontainers
class StoreSessionFoundationHttpIntegrationTest {

    private static final String COOKIE_NAME = "TIEAT_SESSION";
    private static final String PASSWORD = "correct-password";
    private static final UUID STORE_ID = UUID.fromString("9d5e37dd-dbe2-40dc-97fb-8e77c89aa4cb");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
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

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("tieat.onboarding.invite-code", () -> StoreOnboardingHttpIntegrationSupport.INVITE_CODE);
    }

    @BeforeEach
    void clearDatabase() {
        jdbcTemplate.execute("""
            truncate table spring_session_attributes, spring_session,
                stores, store_catalog_entries, store_accounts, partner_organizations, meal_contracts
                restart identity cascade
            """);
    }

    @Test
    void formLoginRoundTrips120CharacterPrincipalWithoutPersistingPasswordHash() throws Exception {
        String loginId = "a".repeat(120);
        seedAccount(loginId);
        SessionHandle initialSession = csrfSession();
        String initialSessionId = initialSession.sessionId();

        MvcResult login = mockMvc.perform(post("/api/v1/sessions")
                .cookie(initialSession.cookie())
                .header("X-CSRF-TOKEN", initialSession.csrfToken())
                .param("loginId", loginId)
                .param("password", PASSWORD))
            .andExpect(status().isNoContent())
            .andReturn();

        SessionHandle authenticatedSession = authenticatedSession(login);
        assertThat(authenticatedSession.sessionId()).isNotEqualTo(initialSessionId);
        assertThat(jdbcTemplate.queryForObject(
            "select principal_name from spring_session where session_id = ?", String.class, authenticatedSession.sessionId()
        )).isEqualTo(loginId);

        Authentication authentication = persistedSecurityContext(authenticatedSession.sessionId()).getAuthentication();
        StoreAccountPrincipal principal = (StoreAccountPrincipal) authentication.getPrincipal();
        assertThat(principal.loginId()).isEqualTo(loginId);
        assertThat(principal.storeId().value()).isEqualTo(STORE_ID);
        assertThat(principal.enabled()).isTrue();
        assertThat(principal.getPassword()).isNull();
        assertThat(serializedSecurityContext(authenticatedSession.sessionId()))
            .doesNotContain(PASSWORD)
            .doesNotContain(jdbcTemplate.queryForObject(
                "select password_hash from store_accounts where login_id = ?", String.class, loginId
            ));

        mockMvc.perform(get("/api/v1/store-onboarding").cookie(authenticatedSession.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.onboardingStatus").value("COMPLETE"))
            .andExpect(jsonPath("$.legacy").value(true));
    }

    @Test
    void signupManualAuthenticationRotatesSessionAndErasesCredentialsBeforePersistence() throws Exception {
        SessionHandle initialSession = csrfSession();
        String initialSessionId = initialSession.sessionId();

        MvcResult signup = mockMvc.perform(post("/api/v1/store-signups")
                .cookie(initialSession.cookie())
                .header("X-CSRF-TOKEN", initialSession.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"inviteCode\":\"" + StoreOnboardingHttpIntegrationSupport.INVITE_CODE
                    + "\",\"loginId\":\"signup-session\",\"password\":\"" + PASSWORD
                    + "\",\"manualStoreName\":\"세션 가게\"}"))
            .andExpect(status().isCreated())
            .andReturn();

        SessionHandle authenticatedSession = authenticatedSession(signup);
        assertThat(authenticatedSession.sessionId()).isNotEqualTo(initialSessionId);
        Authentication authentication = persistedSecurityContext(authenticatedSession.sessionId()).getAuthentication();
        assertThat(((StoreAccountPrincipal) authentication.getPrincipal()).getPassword()).isNull();
        assertThat(serializedSecurityContext(authenticatedSession.sessionId())).doesNotContain(PASSWORD);
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from spring_session where session_id = ?", Long.class, authenticatedSession.sessionId()
        )).isEqualTo(1);
    }

    @Test
    void logoutRequiresCsrfAndDeletesJdbcSessionAndOldCookieCannotAuthenticate() throws Exception {
        seedAccount("logout-store");
        SessionHandle authenticatedSession = login("logout-store", true);
        String sessionId = authenticatedSession.sessionId();
        Cookie cookie = authenticatedSession.cookie();
        String csrfToken = csrfToken(cookie);

        mockMvc.perform(post("/api/v1/sessions/logout").secure(true).cookie(cookie))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("CSRF_TOKEN_INVALID"));
        assertSessionExists(sessionId);

        mockMvc.perform(post("/api/v1/sessions/logout").secure(true).cookie(cookie).header("X-CSRF-TOKEN", "bad-token"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("CSRF_TOKEN_INVALID"));
        assertSessionExists(sessionId);

        MvcResult logout = mockMvc.perform(post("/api/v1/sessions/logout")
                .secure(true)
                .cookie(cookie)
                .header("X-CSRF-TOKEN", csrfToken))
            .andExpect(status().isNoContent())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString(COOKIE_NAME + "=")))
            .andReturn();
        assertThat(logout.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
            .anySatisfy(setCookie -> assertThat(setCookie)
                .contains(COOKIE_NAME + "=")
                .contains("Max-Age=0")
                .contains("Path=/"))
            .anySatisfy(setCookie -> assertThat(setCookie)
                .contains(COOKIE_NAME + "=")
                .contains("Max-Age=0")
                .contains("Path=/")
                .contains("; Secure"));
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from spring_session where session_id = ?", Long.class, sessionId
        )).isZero();

        mockMvc.perform(get("/api/v1/store-onboarding").cookie(cookie))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void anonymousRequestsToActuatorAndOpenApiRoutesRequireAuthentication() throws Exception {
        for (String path : new String[] {"/actuator/health", "/actuator/info", "/v3/api-docs"}) {
            mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        }
    }

    @Test
    void authenticatedPrincipalCanReadActuatorAndOpenApiRoutes() throws Exception {
        for (String path : new String[] {"/actuator/health", "/actuator/info", "/v3/api-docs"}) {
            mockMvc.perform(get(path).with(user("authenticated-reader")))
                .andExpect(status().isOk());
        }
    }

    @Test
    void sessionCookieUsesSafeFlagsAndTracksHttpOrHttpsRequest() throws Exception {
        String httpCookie = mockMvc.perform(get("/api/v1/csrf").secure(false))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andReturn()
            .getResponse()
            .getHeader(HttpHeaders.SET_COOKIE);
        assertThat(httpCookie)
            .contains(COOKIE_NAME + "=")
            .contains("Path=/")
            .contains("HttpOnly")
            .contains("SameSite=Lax")
            .doesNotContain("; Secure");

        String httpsCookie = mockMvc.perform(get("/api/v1/csrf").secure(true))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andReturn()
            .getResponse()
            .getHeader(HttpHeaders.SET_COOKIE);
        assertThat(httpsCookie)
            .contains(COOKIE_NAME + "=")
            .contains("Path=/")
            .contains("HttpOnly")
            .contains("SameSite=Lax")
            .contains("; Secure");

        new ApplicationContextRunner()
            .withUserConfiguration(SessionCookieConfiguration.class)
            .withPropertyValues("tieat.session.cookie.secure=true")
            .run(context -> {
                assertThat(context.getStartupFailure()).isNull();
                CookieSerializer serializer = context.getBean(CookieSerializer.class);
                MockHttpServletRequest request = new MockHttpServletRequest();
                MockHttpServletResponse response = new MockHttpServletResponse();
                serializer.writeCookieValue(new CookieSerializer.CookieValue(request, response, "session-id"));
                assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).contains("; Secure");
            });

        new ApplicationContextRunner()
            .withUserConfiguration(SessionCookieConfiguration.class)
            .withPropertyValues("tieat.session.cookie.secure=tru")
            .run(context -> assertThat(context.getStartupFailure()).isNotNull());
    }

    private void seedAccount(String loginId) {
        jdbcTemplate.update(
            "insert into store_accounts (login_id, password_hash, store_id, enabled) values (?, ?, ?, true)",
            loginId, passwordEncoder.encode(PASSWORD), STORE_ID
        );
    }

    private SessionHandle login(String loginId) throws Exception {
        return login(loginId, false);
    }

    private SessionHandle login(String loginId, boolean secure) throws Exception {
        SessionHandle session = csrfSession(secure);
        MvcResult result = mockMvc.perform(post("/api/v1/sessions")
                .secure(secure)
                .cookie(session.cookie())
                .header("X-CSRF-TOKEN", session.csrfToken())
                .param("loginId", loginId)
                .param("password", PASSWORD))
            .andExpect(status().isNoContent())
            .andReturn();
        return authenticatedSession(result);
    }

    private SessionHandle csrfSession() throws Exception {
        return csrfSession(false);
    }

    private SessionHandle csrfSession(boolean secure) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/csrf").secure(secure))
            .andExpect(status().isOk())
            .andReturn();
        Cookie cookie = responseCookie(result);
        return new SessionHandle(cookie, decodeSessionId(cookie.getValue()), csrfToken(result));
    }

    private SessionHandle authenticatedSession(MvcResult result) throws Exception {
        Cookie cookie = responseCookie(result);
        return new SessionHandle(cookie, decodeSessionId(cookie.getValue()), csrfToken(cookie));
    }

    private String csrfToken(Cookie cookie) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/csrf").cookie(cookie))
            .andExpect(status().isOk())
            .andReturn();
        return csrfToken(result);
    }

    private String csrfToken(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private String serializedSecurityContext(String sessionId) {
        return new String(serializedSecurityContextBytes(sessionId), StandardCharsets.ISO_8859_1);
    }

    private SecurityContext persistedSecurityContext(String sessionId) throws Exception {
        try (ObjectInputStream input = new ObjectInputStream(
            new ByteArrayInputStream(serializedSecurityContextBytes(sessionId)))) {
            return (SecurityContext) input.readObject();
        }
    }

    private byte[] serializedSecurityContextBytes(String sessionId) {
        return jdbcTemplate.queryForObject(
            """
            select a.attribute_bytes
            from spring_session_attributes a
            join spring_session s on s.primary_id = a.session_primary_id
            where s.session_id = ? and a.attribute_name = 'SPRING_SECURITY_CONTEXT'
            """,
            byte[].class,
            sessionId
        );
    }

    private void assertSessionExists(String sessionId) {
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from spring_session where session_id = ?", Long.class, sessionId
        )).isEqualTo(1);
    }

    private Cookie responseCookie(MvcResult result) {
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).startsWith(COOKIE_NAME + "=");
        String value = setCookie.substring(COOKIE_NAME.length() + 1, setCookie.indexOf(';'));
        return new Cookie(COOKIE_NAME, value);
    }

    private String decodeSessionId(String cookieValue) {
        return new String(Base64.getDecoder().decode(cookieValue), StandardCharsets.UTF_8);
    }

    private record SessionHandle(Cookie cookie, String sessionId, String csrfToken) {
    }
}
