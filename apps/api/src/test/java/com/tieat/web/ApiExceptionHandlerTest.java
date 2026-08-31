package com.tieat.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(OutputCaptureExtension.class)
class ApiExceptionHandlerTest {

    private final ProblemDetailFactory problemDetailFactory = new ProblemDetailFactory(new ObjectMapper());
    private final ApiExceptionHandler handler = new ApiExceptionHandler(problemDetailFactory);

    @Test
    void mapsOptimisticLockConflictToStableProblemDetail() {
        var response = handler.handleOptimisticConflict(
            new OptimisticLockingFailureException("stale entity"),
            request()
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getProperties()).containsEntry(
            "errorCode", "MEAL_USAGE_CONFIRMATION_CONFLICT"
        );
    }

    @Test
    void mapsUnexpectedExceptionsWithoutLeakingInternalMessage(CapturedOutput output) {
        String sensitiveMessage = "internal UUID 019c0f9c-6d58-7d37-b0e3-1af21f7124b9";
        var response = handler.handleUnexpected(
            new IllegalStateException(sensitiveMessage),
            request()
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getDetail()).isEqualTo("An internal error occurred");
        assertThat(response.getBody().getProperties()).containsEntry("errorCode", "INTERNAL_SERVER_ERROR");
        assertThat(output)
            .contains("operational_event=api_error")
            .contains("exceptionType=java.lang.IllegalStateException")
            .doesNotContain(sensitiveMessage);
    }

    @Test
    void sanitizesPublicQrProblemInstancesAndKeepsAuthenticatedRouteInstances() throws Exception {
        String rawToken = "qR8wszyH5CXUTpt-Np5deNiRFi9OKKcjPCAwXWpEM5s";
        MockHttpServletRequest publicRequest = new MockHttpServletRequest(
            "POST", "/api/v1/public/meal-usage-qr/" + rawToken + "/meal-usages"
        );
        MockHttpServletResponse publicResponse = new MockHttpServletResponse();

        problemDetailFactory.write(
            publicRequest,
            publicResponse,
            HttpStatus.BAD_REQUEST,
            "VALIDATION_FAILED",
            "Request validation failed"
        );

        assertThat(problemDetailFactory.create(
            publicRequest, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed"
        ).getInstance()).hasToString("/api/v1/public/meal-usage-qr");
        assertThat(publicResponse.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(publicResponse.getContentAsString()).doesNotContain(rawToken);
        assertThat(problemDetailFactory.create(
            request(), HttpStatus.CONFLICT, "MEAL_USAGE_CONFIRMATION_CONFLICT", "Conflict"
        ).getInstance()).hasToString("/api/v1/meal-usages/example/confirmations");
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/api/v1/meal-usages/example/confirmations");
    }
}
