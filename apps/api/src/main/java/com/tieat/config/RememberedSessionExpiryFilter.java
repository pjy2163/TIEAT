package com.tieat.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.web.filter.OncePerRequestFilter;

final class RememberedSessionExpiryFilter extends OncePerRequestFilter {

    private final Clock clock;

    RememberedSessionExpiryFilter(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && Boolean.TRUE.equals(
            session.getAttribute(RememberedSessionPolicy.REMEMBERED_ATTRIBUTE)
        )) {
            Object absoluteExpiresAt = session.getAttribute(RememberedSessionPolicy.ABSOLUTE_EXPIRES_AT_ATTRIBUTE);
            if (!(absoluteExpiresAt instanceof Long expiresAt)
                || Instant.now(clock).toEpochMilli() >= expiresAt) {
                session.invalidate();
            }
        }
        filterChain.doFilter(request, response);
    }
}
