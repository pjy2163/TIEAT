package com.tieat.security.web;

import com.tieat.security.application.RateLimiter;
import com.tieat.security.application.RateLimiter.Decision;
import com.tieat.web.ProblemDetailFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

public final class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final ProblemDetailFactory problemDetailFactory;

    public RateLimitFilter(RateLimiter rateLimiter, ProblemDetailFactory problemDetailFactory) {
        this.rateLimiter = rateLimiter;
        this.problemDetailFactory = problemDetailFactory;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
            || !request.getRequestURI().equals(request.getContextPath() + "/api/v1/sessions");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain filterChain
    ) throws ServletException, IOException {
        Decision decision = rateLimiter.check(RateLimitKeys.login(
            request.getRemoteAddr(), request.getParameter("loginId")
        ));
        if (!decision.allowed()) {
            problemDetailFactory.writeRateLimited(request, response, decision.retryAfter().toSeconds());
            return;
        }
        filterChain.doFilter(request, response);
    }
}
