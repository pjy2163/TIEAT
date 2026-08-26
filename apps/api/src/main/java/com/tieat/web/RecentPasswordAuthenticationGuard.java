package com.tieat.web;

import com.tieat.config.RememberedSessionPolicy;
import com.tieat.identity.application.PasswordReauthenticationRequiredException;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
final class RecentPasswordAuthenticationGuard {

    private final Clock clock;

    RecentPasswordAuthenticationGuard(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    void requireRecent(HttpSession session) {
        Object value = session == null
            ? null
            : session.getAttribute(RememberedSessionPolicy.STRONG_AUTHENTICATED_AT_ATTRIBUTE);
        if (!(value instanceof Long strongAuthenticatedAt)) {
            throw new PasswordReauthenticationRequiredException();
        }
        long now = Instant.now(clock).toEpochMilli();
        try {
            long expiresAt = Math.addExact(
                strongAuthenticatedAt,
                RememberedSessionPolicy.STRONG_AUTHENTICATION_WINDOW.toMillis()
            );
            if (strongAuthenticatedAt > now || now >= expiresAt) {
                throw new PasswordReauthenticationRequiredException();
            }
        } catch (ArithmeticException exception) {
            throw new PasswordReauthenticationRequiredException();
        }
    }
}
