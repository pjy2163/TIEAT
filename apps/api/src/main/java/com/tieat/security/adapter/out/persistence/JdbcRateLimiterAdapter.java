package com.tieat.security.adapter.out.persistence;

import com.tieat.security.application.RateLimiter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcRateLimiterAdapter implements RateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final Duration BLOCK = Duration.ofMinutes(15);
    private static final Duration[] BACKOFF = {
        Duration.ZERO,
        Duration.ofSeconds(1),
        Duration.ofSeconds(5),
        Duration.ofSeconds(15),
        Duration.ofSeconds(30),
        BLOCK
    };

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public JdbcRateLimiterAdapter(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional
    public Decision check(List<Key> keys) {
        Instant now = clock.instant();
        cleanupExpired(now);
        Duration retryAfter = Duration.ZERO;
        for (Key key : keys) {
            RateRow row = lockCurrent(key, now);
            if (row != null) {
                retryAfter = max(retryAfter, retryAfter(row, now));
            }
        }
        return retryAfter.isZero() ? Decision.permitted() : Decision.limited(retryAfter);
    }

    @Override
    @Transactional
    public Decision recordFailure(List<Key> keys) {
        Instant now = clock.instant();
        cleanupExpired(now);
        Duration retryAfter = Duration.ZERO;
        for (Key key : keys) {
            RateRow row = lockCurrent(key, now);
            if (row != null && !retryAfter(row, now).isZero()) {
                retryAfter = max(retryAfter, retryAfter(row, now));
                continue;
            }
            int attempts = row == null ? 0 : row.failedAttempts();
            attempts++;
            Instant blockedUntil = attempts >= 5 ? now.plus(BLOCK) : null;
            Instant availableAt = now.plus(BACKOFF[Math.min(attempts, BACKOFF.length - 1)]);
            if (row == null) {
                int inserted = jdbcTemplate.update(
                    "insert into auth_abuse_rate_limits "
                        + "(scope, key_hash, window_started_at, failed_attempts, available_at, blocked_until) "
                        + "values (?, ?, ?, ?, ?, ?) on conflict (scope, key_hash) do nothing",
                    key.scope(), hash(key.value()), Timestamp.from(now), attempts, Timestamp.from(availableAt),
                    blockedUntil == null ? null : Timestamp.from(blockedUntil)
                );
                if (inserted == 0) {
                    RateRow current = lockCurrent(key, now);
                    if (current == null) {
                        throw new IllegalStateException("Rate limit row disappeared during insert contention");
                    }
                    attempts = current.failedAttempts() + 1;
                    blockedUntil = attempts >= 5 ? now.plus(BLOCK) : null;
                    availableAt = now.plus(BACKOFF[Math.min(attempts, BACKOFF.length - 1)]);
                    jdbcTemplate.update(
                        "update auth_abuse_rate_limits set failed_attempts = ?, available_at = ?, blocked_until = ? "
                            + "where scope = ? and key_hash = ?",
                        attempts, Timestamp.from(availableAt), blockedUntil == null ? null : Timestamp.from(blockedUntil),
                        key.scope(), hash(key.value())
                    );
                }
            } else {
                jdbcTemplate.update(
                    "update auth_abuse_rate_limits set failed_attempts = ?, available_at = ?, blocked_until = ? "
                        + "where scope = ? and key_hash = ?",
                    attempts, Timestamp.from(availableAt), blockedUntil == null ? null : Timestamp.from(blockedUntil),
                    key.scope(), hash(key.value())
                );
            }
            RateRow updated = new RateRow(now, attempts, availableAt, blockedUntil);
            if (attempts >= 5) {
                retryAfter = max(retryAfter, retryAfter(updated, now));
            }
        }
        return retryAfter.isZero() ? Decision.permitted() : Decision.limited(retryAfter);
    }

    @Override
    @Transactional
    public Decision consume(Key key, int limit, Duration window) {
        Objects.requireNonNull(key, "Rate limit key must be supplied");
        Objects.requireNonNull(window, "Rate limit window must be supplied");
        if (limit < 1 || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("Rate limit and window must be positive");
        }

        Instant now = clock.instant();
        cleanupExpired(now);
        while (true) {
            RateRow row = lockCurrent(key, now);
            if (row == null) {
                int inserted = jdbcTemplate.update(
                    "insert into auth_abuse_rate_limits "
                        + "(scope, key_hash, window_started_at, failed_attempts, available_at, blocked_until) "
                        + "values (?, ?, ?, 1, ?, null) on conflict (scope, key_hash) do nothing",
                    key.scope(), hash(key.value()), Timestamp.from(now), Timestamp.from(now)
                );
                if (inserted == 1) {
                    return Decision.permitted();
                }
                continue;
            }

            Instant resetsAt = row.windowStartedAt().plus(window);
            if (!now.isBefore(resetsAt)) {
                jdbcTemplate.update(
                    "update auth_abuse_rate_limits set window_started_at = ?, failed_attempts = 1, "
                        + "available_at = ?, blocked_until = null where scope = ? and key_hash = ?",
                    Timestamp.from(now), Timestamp.from(now), key.scope(), hash(key.value())
                );
                return Decision.permitted();
            }
            if (row.failedAttempts() >= limit) {
                return Decision.limited(remaining(resetsAt, now));
            }
            jdbcTemplate.update(
                "update auth_abuse_rate_limits set failed_attempts = failed_attempts + 1 "
                    + "where scope = ? and key_hash = ?",
                key.scope(), hash(key.value())
            );
            return Decision.permitted();
        }
    }

    @Override
    @Transactional
    public void clear(List<Key> keys) {
        for (Key key : keys) {
            jdbcTemplate.update(
                "delete from auth_abuse_rate_limits where scope = ? and key_hash = ?",
                key.scope(), hash(key.value())
            );
        }
    }

    private RateRow lockCurrent(Key key, Instant now) {
        String hash = hash(key.value());
        List<RateRow> rows = jdbcTemplate.query(
            "select window_started_at, failed_attempts, available_at, blocked_until "
                + "from auth_abuse_rate_limits where scope = ? and key_hash = ? for update",
            (resultSet, rowNum) -> new RateRow(
                resultSet.getTimestamp("window_started_at").toInstant(),
                resultSet.getInt("failed_attempts"),
                resultSet.getTimestamp("available_at").toInstant(),
                resultSet.getTimestamp("blocked_until") == null
                    ? null : resultSet.getTimestamp("blocked_until").toInstant()
            ),
            key.scope(), hash
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void cleanupExpired(Instant now) {
        jdbcTemplate.update(
            "delete from auth_abuse_rate_limits "
                + "where window_started_at < ? and available_at <= ? "
                + "and (blocked_until is null or blocked_until <= ?)",
            Timestamp.from(now.minus(WINDOW)),
            Timestamp.from(now),
            Timestamp.from(now)
        );
    }

    private Duration retryAfter(RateRow row, Instant now) {
        Duration retryAfter = Duration.ZERO;
        if (row.availableAt() != null && row.availableAt().isAfter(now)) {
            retryAfter = max(retryAfter, remaining(row.availableAt(), now));
        }
        if (row.blockedUntil() != null && row.blockedUntil().isAfter(now)) {
            retryAfter = max(retryAfter, remaining(row.blockedUntil(), now));
        }
        return retryAfter;
    }

    private Duration remaining(Instant until, Instant now) {
        long remainingMillis = Duration.between(now, until).toMillis();
        return Duration.ofSeconds(Math.max(1, (remainingMillis + 999) / 1_000));
    }

    private Duration max(Duration first, Duration second) {
        return first.compareTo(second) >= 0 ? first : second;
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record RateRow(
        Instant windowStartedAt,
        int failedAttempts,
        Instant availableAt,
        Instant blockedUntil
    ) {
    }
}
