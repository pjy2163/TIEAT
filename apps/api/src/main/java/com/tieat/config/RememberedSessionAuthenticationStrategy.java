package com.tieat.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationException;

final class RememberedSessionAuthenticationStrategy implements SessionAuthenticationStrategy {

    private final SessionAuthenticationStrategy delegate;
    private final Clock clock;

    RememberedSessionAuthenticationStrategy(SessionAuthenticationStrategy delegate, Clock clock) {
        this.delegate = Objects.requireNonNull(delegate);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void onAuthentication(
        Authentication authentication,
        HttpServletRequest request,
        HttpServletResponse response
    ) throws SessionAuthenticationException {
        boolean remembered = RememberedSessionPolicy.isRememberedLogin(request);
        if (remembered) {
            request.setAttribute(
                RememberedSessionPolicy.COOKIE_MAX_AGE_ATTRIBUTE,
                RememberedSessionPolicy.REMEMBERED_COOKIE_MAX_AGE_SECONDS
            );
        } else {
            request.removeAttribute(RememberedSessionPolicy.COOKIE_MAX_AGE_ATTRIBUTE);
        }

        delegate.onAuthentication(authentication, request, response);

        HttpSession session = request.getSession(false);
        if (session != null) {
            long now = Instant.now(clock).toEpochMilli();
            session.setAttribute(RememberedSessionPolicy.REMEMBERED_ATTRIBUTE, remembered);
            session.setAttribute(RememberedSessionPolicy.STRONG_AUTHENTICATED_AT_ATTRIBUTE, now);
            session.setMaxInactiveInterval(remembered
                ? RememberedSessionPolicy.REMEMBERED_MAX_INACTIVE_INTERVAL_SECONDS
                : RememberedSessionPolicy.ORDINARY_MAX_INACTIVE_INTERVAL_SECONDS);
            if (remembered) {
                session.setAttribute(
                    RememberedSessionPolicy.ABSOLUTE_EXPIRES_AT_ATTRIBUTE,
                    Instant.ofEpochMilli(now).plus(RememberedSessionPolicy.REMEMBERED_ABSOLUTE_LIFETIME).toEpochMilli()
                );
            } else {
                session.removeAttribute(RememberedSessionPolicy.ABSOLUTE_EXPIRES_AT_ATTRIBUTE);
            }
        }
    }
}
