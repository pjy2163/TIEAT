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
        if (isPublicMealUsageQrRequest(request)) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        }
        objectMapper.writeValue(response.getOutputStream(), create(request, status, errorCode, detail));
    }

    boolean isPublicMealUsageQrRequest(HttpServletRequest request) {
        String publicQrPrefix = request.getContextPath() + "/api/v1/public/meal-usage-qr/";
        return request.getRequestURI().startsWith(publicQrPrefix);
    }
}
