package com.tieat.identity;
import static org.assertj.core.api.Assertions.assertThat;
import com.tieat.TieatApiApplication;
import com.tieat.config.RememberedSessionPolicy;
import com.tieat.identity.adapter.out.persistence.JdbcSessionAuthenticationStateStore;
import com.tieat.identity.application.SessionReauthenticationService;
import com.tieat.store.domain.StoreId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
@SpringBootTest(classes = TieatApiApplication.class)
@Import(SessionReauthenticationServiceIntegrationTest.TestClockConfiguration.class)
@Testcontainers
class SessionReauthenticationServiceIntegrationTest {
    private static final String LOGIN_ID = "reauth-store";
    private static final String PASSWORD = "correct-password";
    private static final String WRONG_PASSWORD = "wrong-password";
    private static final StoreId STORE_ID = new StoreId(UUID.fromString("9d5e37dd-dbe2-40dc-97fb-8e77c89aa4cb"));
    private static final Instant BASE_TIME = Instant.parse("2026-08-24T00:00:00Z");
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
        .withDatabaseName("tieat")
        .withUsername("tieat")
        .withPassword("tieat");
    @Autowired
    private SessionReauthenticationService service;
    @Autowired
    private JdbcSessionAuthenticationStateStore stateStore;
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
    }
    @BeforeEach
    void clearDatabase() {
        clock.set(BASE_TIME);
        jdbcTemplate.execute("truncate table spring_session_attributes, spring_session, store_accounts restart identity cascade");
        seedAccount();
    }
    @Test
    void fourFailuresThenCorrectPasswordClearsWindowAndSetsStrongTime() {
        String sessionId = seedSession(false);

        for (int attempt = 0; attempt < 4; attempt++) {
            assertStatus(sessionId, WRONG_PASSWORD, SessionReauthenticationService.Result.Status.FAILED);
        }
        assertAttribute(sessionId, RememberedSessionPolicy.REAUTH_FAILED_ATTEMPTS_ATTRIBUTE, 4);
        assertAttribute(sessionId, RememberedSessionPolicy.REAUTH_FAILURE_WINDOW_STARTED_AT_ATTRIBUTE, BASE_TIME.toEpochMilli());
        SessionReauthenticationService.Result success = reauthenticate(sessionId, PASSWORD);
        assertThat(success.status()).isEqualTo(SessionReauthenticationService.Result.Status.SUCCESS);
        assertThat(success.remainingCookieMaxAgeSeconds()).isNull();
        assertAttribute(sessionId, RememberedSessionPolicy.REAUTH_FAILED_ATTEMPTS_ATTRIBUTE, null);
        assertAttribute(sessionId, RememberedSessionPolicy.REAUTH_FAILURE_WINDOW_STARTED_AT_ATTRIBUTE, null);
        assertAttribute(sessionId, RememberedSessionPolicy.STRONG_AUTHENTICATED_AT_ATTRIBUTE, BASE_TIME.toEpochMilli());
    }
    @Test
    void fifthFailureStaysLockedBeforeAndResetsAtExactWindowBoundary() {
        String sessionId = seedSession(false);
        for (int attempt = 0; attempt < 5; attempt++) {
            reauthenticate(sessionId, WRONG_PASSWORD);
        }
        clock.advance(Duration.ofMinutes(15).minusMillis(1));
        assertStatus(sessionId, PASSWORD, SessionReauthenticationService.Result.Status.FAILED);
        assertAttribute(sessionId, RememberedSessionPolicy.REAUTH_FAILED_ATTEMPTS_ATTRIBUTE, 5);
        assertAttribute(sessionId, RememberedSessionPolicy.REAUTH_FAILURE_WINDOW_STARTED_AT_ATTRIBUTE, BASE_TIME.toEpochMilli());
        clock.advance(Duration.ofMillis(1));
        assertStatus(sessionId, PASSWORD, SessionReauthenticationService.Result.Status.SUCCESS);
        assertAttribute(sessionId, RememberedSessionPolicy.REAUTH_FAILED_ATTEMPTS_ATTRIBUTE, null);
        assertAttribute(sessionId, RememberedSessionPolicy.REAUTH_FAILURE_WINDOW_STARTED_AT_ATTRIBUTE, null);
        assertAttribute(sessionId, RememberedSessionPolicy.STRONG_AUTHENTICATED_AT_ATTRIBUTE,
            BASE_TIME.plus(Duration.ofMinutes(15)).toEpochMilli());
    }
    @Test
    void sixParallelWrongAttemptsPersistExactlyFiveFailures() throws Exception {
        String sessionId = seedSession(false);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(6);
        List<Future<SessionReauthenticationService.Result>> futures = new ArrayList<>();
        try {
            for (int attempt = 0; attempt < 6; attempt++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return reauthenticate(sessionId, WRONG_PASSWORD);
                }));
            }
            start.countDown();
            List<SessionReauthenticationService.Result> results = new ArrayList<>();
            for (Future<SessionReauthenticationService.Result> future : futures) {
                results.add(future.get());
            }
            assertThat(results).hasSize(6).allMatch(result ->
                result.status() == SessionReauthenticationService.Result.Status.FAILED
            );
        } finally {
            executor.shutdownNow();
        }
        assertAttribute(sessionId, RememberedSessionPolicy.REAUTH_FAILED_ATTEMPTS_ATTRIBUTE, 5);
        assertAttribute(sessionId, RememberedSessionPolicy.REAUTH_FAILURE_WINDOW_STARTED_AT_ATTRIBUTE, BASE_TIME.toEpochMilli());
        assertStatus(sessionId, PASSWORD, SessionReauthenticationService.Result.Status.FAILED);
    }
    @Test
    void unavailableOrDisabledStatesFailClosedWithoutStrongAuthentication() {
        assertThat(reauthenticate("missing-session", PASSWORD).status())
            .isEqualTo(SessionReauthenticationService.Result.Status.SESSION_UNAVAILABLE);
        assertUnavailable(seedSession(false, BASE_TIME.minusMillis(1).toEpochMilli(), LOGIN_ID, null));
        assertUnavailable(seedSession(true, BASE_TIME.plus(Duration.ofDays(7)).toEpochMilli(), LOGIN_ID, null));
        assertUnavailable(seedSession(true, BASE_TIME.plus(Duration.ofDays(7)).toEpochMilli(), LOGIN_ID, BASE_TIME.toEpochMilli()));
        assertUnavailable(seedSession(false, BASE_TIME.plusSeconds(1_800).toEpochMilli(), "other-login", null));
        String corrupt = seedSession(false);
        JdbcSessionAuthenticationStateStore.SessionRow corruptRow = stateStore.lock(corrupt).orElseThrow();
        stateStore.upsertAttribute(corruptRow, RememberedSessionPolicy.REAUTH_FAILED_ATTEMPTS_ATTRIBUTE, 5);
        stateStore.upsertAttribute(corruptRow, RememberedSessionPolicy.REAUTH_FAILURE_WINDOW_STARTED_AT_ATTRIBUTE, Long.MAX_VALUE);
        assertUnavailable(corrupt);
        jdbcTemplate.update("update store_accounts set enabled = false where login_id = ?", LOGIN_ID);
        String disabled = seedSession(false);
        assertStatus(disabled, PASSWORD, SessionReauthenticationService.Result.Status.FAILED);
        assertAttribute(disabled, RememberedSessionPolicy.STRONG_AUTHENTICATED_AT_ATTRIBUTE, null);
    }
    @Test
    void rememberedSuccessKeepsAbsoluteExpiryAndReturnsRemainingCookieLifetime() {
        String sessionId = seedSession(true);
        long absoluteExpiresAt = (Long) attribute(sessionId, RememberedSessionPolicy.ABSOLUTE_EXPIRES_AT_ATTRIBUTE);
        clock.advance(Duration.ofDays(5).plusMillis(1_234));
        SessionReauthenticationService.Result success = reauthenticate(sessionId, PASSWORD);
        long expectedRemaining = Duration.ofDays(25).minusMillis(1_234).toSeconds();
        assertThat(success.status()).isEqualTo(SessionReauthenticationService.Result.Status.SUCCESS);
        assertThat(success.remainingCookieMaxAgeSeconds()).isEqualTo((int) expectedRemaining);
        assertAttribute(sessionId, RememberedSessionPolicy.ABSOLUTE_EXPIRES_AT_ATTRIBUTE, absoluteExpiresAt);
        assertAttribute(sessionId, RememberedSessionPolicy.STRONG_AUTHENTICATED_AT_ATTRIBUTE, clock.instant().toEpochMilli());
        assertAttribute(sessionId, RememberedSessionPolicy.REAUTH_FAILED_ATTEMPTS_ATTRIBUTE, null);
        assertAttribute(sessionId, RememberedSessionPolicy.REAUTH_FAILURE_WINDOW_STARTED_AT_ATTRIBUTE, null);
    }
    private SessionReauthenticationService.Result reauthenticate(String sessionId, String password) {
        return service.reauthenticate(sessionId, LOGIN_ID, STORE_ID, password);
    }
    private void assertStatus(
        String sessionId, String password, SessionReauthenticationService.Result.Status expected
    ) {
        assertThat(reauthenticate(sessionId, password).status()).isEqualTo(expected);
    }
    private void assertUnavailable(String sessionId) {
        assertStatus(sessionId, PASSWORD, SessionReauthenticationService.Result.Status.SESSION_UNAVAILABLE);
        assertAttribute(sessionId, RememberedSessionPolicy.STRONG_AUTHENTICATED_AT_ATTRIBUTE, null);
    }
    private String seedSession(boolean remembered) {
        long now = clock.instant().toEpochMilli();
        int maxInactiveInterval = remembered
            ? RememberedSessionPolicy.REMEMBERED_MAX_INACTIVE_INTERVAL_SECONDS
            : RememberedSessionPolicy.ORDINARY_MAX_INACTIVE_INTERVAL_SECONDS;
        return seedSession(
            remembered, now + maxInactiveInterval * 1_000L, LOGIN_ID,
            remembered ? BASE_TIME.plus(RememberedSessionPolicy.REMEMBERED_ABSOLUTE_LIFETIME).toEpochMilli() : null
        );
    }
    private String seedSession(boolean remembered, long expiryTime, String principalName, Long absoluteExpiresAt) {
        String sessionId = UUID.randomUUID().toString();
        long now = clock.instant().toEpochMilli();
        int maxInactiveInterval = remembered
            ? RememberedSessionPolicy.REMEMBERED_MAX_INACTIVE_INTERVAL_SECONDS
            : RememberedSessionPolicy.ORDINARY_MAX_INACTIVE_INTERVAL_SECONDS;
        jdbcTemplate.update(
            """
            insert into spring_session
                (primary_id, session_id, creation_time, last_access_time, max_inactive_interval, expiry_time, principal_name)
            values (?, ?, ?, ?, ?, ?, ?)
            """,
            sessionId, sessionId, now, now, maxInactiveInterval, expiryTime, principalName
        );
        JdbcSessionAuthenticationStateStore.SessionRow row =
            new JdbcSessionAuthenticationStateStore.SessionRow(sessionId, expiryTime, principalName);
        if (remembered) {
            stateStore.upsertAttribute(row, RememberedSessionPolicy.REMEMBERED_ATTRIBUTE, true);
            if (absoluteExpiresAt != null) {
                stateStore.upsertAttribute(row, RememberedSessionPolicy.ABSOLUTE_EXPIRES_AT_ATTRIBUTE, absoluteExpiresAt);
            }
        }
        return sessionId;
    }
    private void seedAccount() {
        jdbcTemplate.update(
            "insert into store_accounts (login_id, password_hash, store_id, enabled) values (?, ?, ?, true)",
            LOGIN_ID, passwordEncoder.encode(PASSWORD), STORE_ID.value()
        );
    }
    private Object attribute(String sessionId, String name) {
        JdbcSessionAuthenticationStateStore.SessionRow row = stateStore.lock(sessionId).orElseThrow();
        return stateStore.readAttribute(row, name);
    }
    private void assertAttribute(String sessionId, String name, Object expected) {
        assertThat(attribute(sessionId, name)).isEqualTo(expected);
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
        MutableClock(Instant current) { this.current = current; }
        void set(Instant current) { this.current = current; }
        void advance(Duration duration) { current = current.plus(duration); }
        @Override
        public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override
        public Clock withZone(ZoneId zone) { return this; }
        @Override
        public Instant instant() { return current; }
    }
}
