package com.tieat.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ProblemDetailFactory {

    private static final URI PUBLIC_MEAL_USAGE_QR_INSTANCE = URI.create("/api/v1/public/meal-usage-qr");

    private final ObjectMapper objectMapper;

    public ProblemDetailFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ProblemDetail create(HttpServletRequest request, HttpStatus status, String errorCode, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:tieat:problem:" + errorCode.toLowerCase()));
        problem.setInstance(isPublicMealUsageQrRequest(request)
            ? PUBLIC_MEAL_USAGE_QR_INSTANCE
            : URI.create(request.getRequestURI()));
        problem.setProperty("errorCode", errorCode);
        return problem;
    }

    public void write(
        HttpServletRequest request,
        HttpServletResponse response,
        HttpStatus status,
        String errorCode,
        String detail
    ) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        if (isPublicMealUsageQrRequest(request)
            || isMonthlyMealUsageRequest(request)
            || isConfirmedMealUsageRequest(request)
            || isPosSettlementRequest(request)
            || isStoreOnboardingRequest(request)
            || isStoreMealUsageQrRequest(request)
            || isMealUsageCreationRequest(request)
            || isSessionReauthenticationRequest(request)
            || isSessionRequest(request)) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        }
        objectMapper.writeValue(response.getOutputStream(), create(request, status, errorCode, detail));
    }

    public void writeRateLimited(
        HttpServletRequest request,
        HttpServletResponse response,
        long retryAfterSeconds
    ) throws IOException {
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        write(request, response, HttpStatus.TOO_MANY_REQUESTS, "REQUEST_RATE_LIMITED", "Too many requests");
    }

    boolean isPublicMealUsageQrRequest(HttpServletRequest request) {
        String publicQrPrefix = request.getContextPath() + "/api/v1/public/meal-usage-qr/";
        return request.getRequestURI().startsWith(publicQrPrefix);
    }

    boolean isPosSettlementRequest(HttpServletRequest request) {
        String settlementPrefix = request.getContextPath() + "/api/v1/pos-settlements";
        return request.getRequestURI().startsWith(settlementPrefix);
    }

    boolean isMonthlyMealUsageRequest(HttpServletRequest request) {
        String monthlyLedgerPrefix = request.getContextPath() + "/api/v1/meal-usages/months/";
        return request.getRequestURI().startsWith(monthlyLedgerPrefix);
    }

    boolean isConfirmedMealUsageRequest(HttpServletRequest request) {
        String confirmedLedgerPrefix = request.getContextPath() + "/api/v1/meal-usages/confirmed";
        return request.getRequestURI().startsWith(confirmedLedgerPrefix);
    }

    boolean isStoreOnboardingRequest(HttpServletRequest request) {
        String onboardingPrefix = request.getContextPath() + "/api/v1/store-";
        return request.getRequestURI().startsWith(onboardingPrefix);
    }

    boolean isStoreMealUsageQrRequest(HttpServletRequest request) {
        String qrPath = request.getContextPath() + "/api/v1/store-meal-usage-qr";
        String requestUri = request.getRequestURI();
        return requestUri.equals(qrPath) || requestUri.startsWith(qrPath + "/");
    }

    boolean isMealUsageCreationRequest(HttpServletRequest request) {
        return request.getRequestURI().equals(request.getContextPath() + "/api/v1/meal-usages");
    }

    boolean isSessionReauthenticationRequest(HttpServletRequest request) {
        String reauthenticationPath = request.getContextPath() + "/api/v1/session-reauthentications";
        return request.getRequestURI().equals(reauthenticationPath);
    }

    boolean isSessionRequest(HttpServletRequest request) {
        return request.getRequestURI().equals(request.getContextPath() + "/api/v1/sessions");
    }
}
