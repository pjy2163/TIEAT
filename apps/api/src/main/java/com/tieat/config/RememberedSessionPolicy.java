package com.tieat.config;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;

public final class RememberedSessionPolicy {

    public static final String REMEMBERED_ATTRIBUTE = "tieat.auth.remembered";
    public static final String ABSOLUTE_EXPIRES_AT_ATTRIBUTE = "tieat.auth.absoluteExpiresAt";
    public static final String STRONG_AUTHENTICATED_AT_ATTRIBUTE = "tieat.auth.strongAuthenticatedAt";
    public static final String REAUTH_FAILED_ATTEMPTS_ATTRIBUTE = "tieat.auth.reauthFailedAttempts";
    public static final String REAUTH_FAILURE_WINDOW_STARTED_AT_ATTRIBUTE = "tieat.auth.reauthFailureWindowStartedAt";
    public static final String COOKIE_MAX_AGE_ATTRIBUTE = "tieat.auth.cookieMaxAge";
    public static final String REMEMBER_LOGIN_PARAMETER = "rememberLogin";
    public static final int ORDINARY_MAX_INACTIVE_INTERVAL_SECONDS = (int) Duration.ofMinutes(30).toSeconds();
    public static final int REMEMBERED_MAX_INACTIVE_INTERVAL_SECONDS = (int) Duration.ofDays(7).toSeconds();
    public static final int REMEMBERED_COOKIE_MAX_AGE_SECONDS = (int) Duration.ofDays(30).toSeconds();
    public static final int REAUTH_MAX_FAILED_ATTEMPTS = 5;
    public static final Duration REAUTH_FAILURE_WINDOW = Duration.ofMinutes(15);
    public static final Duration STRONG_AUTHENTICATION_WINDOW = Duration.ofMinutes(10);
    public static final Duration REMEMBERED_ABSOLUTE_LIFETIME = Duration.ofDays(30);

    private RememberedSessionPolicy() {
    }

    static boolean isRememberedLogin(HttpServletRequest request) {
        return "POST".equals(request.getMethod())
            && "/api/v1/sessions".equals(request.getRequestURI())
            && "true".equals(request.getParameter(REMEMBER_LOGIN_PARAMETER));
    }
}
