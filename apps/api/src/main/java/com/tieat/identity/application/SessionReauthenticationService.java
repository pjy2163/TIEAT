package com.tieat.identity.application;

import com.tieat.config.RememberedSessionPolicy;
import com.tieat.identity.adapter.out.persistence.JdbcSessionAuthenticationStateStore;
import com.tieat.identity.domain.StoreAccount;
import com.tieat.identity.domain.StoreAccountRepository;
import com.tieat.store.domain.StoreId;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionReauthenticationService {
    private static final long MILLIS_PER_SECOND = 1_000L;
    private final JdbcSessionAuthenticationStateStore stateStore;
    private final StoreAccountRepository storeAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    public SessionReauthenticationService(
        JdbcSessionAuthenticationStateStore stateStore,
        StoreAccountRepository storeAccountRepository,
        PasswordEncoder passwordEncoder,
        Clock clock
    ) {
        this.stateStore = Objects.requireNonNull(stateStore);
        this.storeAccountRepository = Objects.requireNonNull(storeAccountRepository);
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder);
        this.clock = Objects.requireNonNull(clock);
    }
    @Transactional
    public Result reauthenticate(
        String rawSessionId,
        String principalLoginId,
        StoreId principalStoreId,
        String opaquePassword
    ) {
        Instant now = Instant.now(clock);
        long nowMillis = now.toEpochMilli();
        if (rawSessionId == null || rawSessionId.isBlank()) {
            return Result.sessionUnavailable();
        }
        JdbcSessionAuthenticationStateStore.SessionRow session = stateStore.lock(rawSessionId).orElse(null);
        if (session == null || session.expiryTime() <= nowMillis) {
            return Result.sessionUnavailable();
        }
        if (session.principalName() == null || !session.principalName().equals(principalLoginId)) {
            return Result.sessionUnavailable();
        }
        boolean remembered = Boolean.TRUE.equals(stateStore.readAttribute(
            session, RememberedSessionPolicy.REMEMBERED_ATTRIBUTE
        ));
        Object absoluteValue = stateStore.readAttribute(session, RememberedSessionPolicy.ABSOLUTE_EXPIRES_AT_ATTRIBUTE);
        Long absoluteExpiresAt = absoluteValue instanceof Long value ? value : null;
        if (remembered && (absoluteExpiresAt == null || absoluteExpiresAt <= nowMillis)) {
            return Result.sessionUnavailable();
        }
        ThrottleState throttle = throttleState(session, nowMillis);
        if (throttle.unavailable()) {
            return Result.sessionUnavailable();
        }
        if (throttle.failedAttempts() >= RememberedSessionPolicy.REAUTH_MAX_FAILED_ATTEMPTS) {
            return Result.failed();
        }
        StoreAccount account = principalLoginId == null
            ? null
            : storeAccountRepository.findByLoginId(principalLoginId).orElse(null);
        boolean correct = account != null
            && account.enabled()
            && account.storeId().equals(principalStoreId)
            && opaquePassword != null
            && !opaquePassword.isBlank()
            && passwordEncoder.matches(opaquePassword, account.passwordHash());
        if (correct) {
            stateStore.upsertAttribute(
                session, RememberedSessionPolicy.STRONG_AUTHENTICATED_AT_ATTRIBUTE, nowMillis
            );
            stateStore.deleteAttribute(session, RememberedSessionPolicy.REAUTH_FAILED_ATTEMPTS_ATTRIBUTE);
            stateStore.deleteAttribute(session, RememberedSessionPolicy.REAUTH_FAILURE_WINDOW_STARTED_AT_ATTRIBUTE);
            return Result.success(remembered ? remainingCookieMaxAge(absoluteExpiresAt, now) : null);
        }
        int nextAttempts = Math.min(
            RememberedSessionPolicy.REAUTH_MAX_FAILED_ATTEMPTS, throttle.failedAttempts() + 1
        );
        stateStore.upsertAttribute(
            session, RememberedSessionPolicy.REAUTH_FAILED_ATTEMPTS_ATTRIBUTE, nextAttempts
        );
        stateStore.upsertAttribute(
            session, RememberedSessionPolicy.REAUTH_FAILURE_WINDOW_STARTED_AT_ATTRIBUTE,
            throttle.windowStartedAt() == null ? nowMillis : throttle.windowStartedAt()
        );
        return Result.failed();
    }
    private ThrottleState throttleState(
        JdbcSessionAuthenticationStateStore.SessionRow session,
        long nowMillis
    ) {
        Object attemptsValue = stateStore.readAttribute(session, RememberedSessionPolicy.REAUTH_FAILED_ATTEMPTS_ATTRIBUTE);
        Object windowValue = stateStore.readAttribute(
            session, RememberedSessionPolicy.REAUTH_FAILURE_WINDOW_STARTED_AT_ATTRIBUTE
        );
        if (attemptsValue == null && windowValue == null) {
            return new ThrottleState(0, null, false);
        }
        if (!(attemptsValue instanceof Integer attempts)
            || !(windowValue instanceof Long windowStartedAt)
            || attempts < 1
            || attempts > RememberedSessionPolicy.REAUTH_MAX_FAILED_ATTEMPTS
            || windowStartedAt < 0
            || windowStartedAt > nowMillis) {
            return new ThrottleState(0, null, true);
        }
        try {
            long windowEndsAt = Math.addExact(windowStartedAt, RememberedSessionPolicy.REAUTH_FAILURE_WINDOW.toMillis());
            return nowMillis >= windowEndsAt
                ? new ThrottleState(0, null, false)
                : new ThrottleState(attempts, windowStartedAt, false);
        } catch (ArithmeticException exception) {
            return new ThrottleState(0, null, true);
        }
    }
    private int remainingCookieMaxAge(Long absoluteExpiresAt, Instant now) {
        long remaining = (absoluteExpiresAt - now.toEpochMilli()) / MILLIS_PER_SECOND;
        long clamped = Math.max(1L, Math.min(RememberedSessionPolicy.REMEMBERED_COOKIE_MAX_AGE_SECONDS, remaining));
        return (int) clamped;
    }
    private record ThrottleState(int failedAttempts, Long windowStartedAt, boolean unavailable) {
    }
    public record Result(Status status, Integer remainingCookieMaxAgeSeconds) {
        public enum Status {
            SUCCESS,
            FAILED,
            SESSION_UNAVAILABLE
        }
        private static Result success(Integer remainingCookieMaxAgeSeconds) {
            return new Result(Status.SUCCESS, remainingCookieMaxAgeSeconds);
        }
        private static Result failed() {
            return new Result(Status.FAILED, null);
        }
        private static Result sessionUnavailable() {
            return new Result(Status.SESSION_UNAVAILABLE, null);
        }
    }
}
