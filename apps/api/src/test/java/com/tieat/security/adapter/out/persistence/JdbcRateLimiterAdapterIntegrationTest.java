package com.tieat.security.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.tieat.qr.domain.MealUsageQrContextId;
import com.tieat.security.application.RateLimiter;
import com.tieat.security.web.RateLimitKeys;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
class JdbcRateLimiterAdapterIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
        .withDatabaseName("tieat")
        .withUsername("tieat")
        .withPassword("tieat");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void clearRateLimits() {
        jdbcTemplate.update("delete from auth_abuse_rate_limits");
    }

    @Test
    void appliesClientCooldownOnlyAfterAllowedThirdAndFourthCreates() {
        String rawClientKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        JdbcRateLimiterAdapter limiter = new JdbcRateLimiterAdapter(
            jdbcTemplate, Clock.fixed(NOW, ZoneOffset.UTC)
        );
        RateLimiter.Key client = RateLimitKeys.publicQrCreateClient(
            new MealUsageQrContextId(UUID.randomUUID()), rawClientKey
        );

        assertThat(limiter.consume(client, 5, Duration.ofMinutes(1))).isEqualTo(RateLimiter.Decision.permitted());
        assertThat(limiter.consume(client, 5, Duration.ofMinutes(1))).isEqualTo(RateLimiter.Decision.permitted());
        assertThat(limiter.consume(client, 5, Duration.ofMinutes(1))).isEqualTo(RateLimiter.Decision.permitted());
        assertThat(count(client)).isEqualTo(3);
        assertThat(availableAt(client)).isEqualTo(NOW.plusSeconds(2));

        assertThat(limiter.consume(client, 5, Duration.ofMinutes(1)))
            .isEqualTo(RateLimiter.Decision.limited(Duration.ofSeconds(2)));
        assertThat(count(client)).isEqualTo(3);

        setAvailableAt(client, NOW.minusSeconds(1));
        assertThat(limiter.consume(client, 5, Duration.ofMinutes(1))).isEqualTo(RateLimiter.Decision.permitted());
        assertThat(count(client)).isEqualTo(4);
        assertThat(availableAt(client)).isEqualTo(NOW.plusSeconds(5));
        assertThat(limiter.consume(client, 5, Duration.ofMinutes(1)))
            .isEqualTo(RateLimiter.Decision.limited(Duration.ofSeconds(5)));

        setAvailableAt(client, NOW.minusSeconds(1));
        assertThat(limiter.consume(client, 5, Duration.ofMinutes(1))).isEqualTo(RateLimiter.Decision.permitted());
        assertThat(count(client)).isEqualTo(5);
        assertThat(limiter.consume(client, 5, Duration.ofMinutes(1)).allowed()).isFalse();

        RateLimiter.Key sameClientOtherContext = RateLimitKeys.publicQrCreateClient(
            new MealUsageQrContextId(UUID.randomUUID()), rawClientKey
        );
        assertThat(limiter.consume(sameClientOtherContext, 5, Duration.ofMinutes(1)))
            .isEqualTo(RateLimiter.Decision.permitted());
        assertThat(count(sameClientOtherContext)).isEqualTo(1);

        Instant resetBoundary = NOW.plusSeconds(60);
        JdbcRateLimiterAdapter boundaryLimiter = new JdbcRateLimiterAdapter(
            jdbcTemplate, Clock.fixed(resetBoundary, ZoneOffset.UTC)
        );
        assertThat(boundaryLimiter.consume(client, 5, Duration.ofMinutes(1)))
            .isEqualTo(RateLimiter.Decision.permitted());
        assertThat(count(client)).isEqualTo(1);
        assertThat(windowStartedAt(client)).isEqualTo(resetBoundary);
    }

    @Test
    void keepsIpLimiterAtFifteenPerMinuteWithoutClientCooldown() {
        JdbcRateLimiterAdapter limiter = new JdbcRateLimiterAdapter(
            jdbcTemplate, Clock.fixed(NOW, ZoneOffset.UTC)
        );
        RateLimiter.Key ip = RateLimitKeys.publicQrCreateIp("198.51.100.20");

        for (int attempt = 0; attempt < 15; attempt++) {
            assertThat(limiter.consume(ip, 15, Duration.ofMinutes(1)).allowed()).isTrue();
        }
        assertThat(limiter.consume(ip, 15, Duration.ofMinutes(1)))
            .isEqualTo(RateLimiter.Decision.limited(Duration.ofMinutes(1)));
        assertThat(availableAt(ip)).isEqualTo(NOW);
    }

    private int count(RateLimiter.Key key) {
        return jdbcTemplate.queryForObject(
            "select failed_attempts from auth_abuse_rate_limits where scope = ? and key_hash = ?",
            Integer.class, key.scope(), hash(key.value())
        );
    }

    private Instant availableAt(RateLimiter.Key key) {
        return jdbcTemplate.queryForObject(
            "select available_at from auth_abuse_rate_limits where scope = ? and key_hash = ?",
            (resultSet, rowNum) -> resultSet.getTimestamp(1).toInstant(), key.scope(), hash(key.value())
        );
    }

    private Instant windowStartedAt(RateLimiter.Key key) {
        return jdbcTemplate.queryForObject(
            "select window_started_at from auth_abuse_rate_limits where scope = ? and key_hash = ?",
            (resultSet, rowNum) -> resultSet.getTimestamp(1).toInstant(), key.scope(), hash(key.value())
        );
    }

    private void setAvailableAt(RateLimiter.Key key, Instant value) {
        jdbcTemplate.update(
            "update auth_abuse_rate_limits set available_at = ? where scope = ? and key_hash = ?",
            java.sql.Timestamp.from(value), key.scope(), hash(key.value())
        );
    }

    private String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            );
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
