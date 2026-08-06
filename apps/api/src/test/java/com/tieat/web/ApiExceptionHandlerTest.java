package com.tieat.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler(new ProblemDetailFactory(new ObjectMapper()));

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
    void mapsUnexpectedExceptionsWithoutLeakingInternalMessage() {
        var response = handler.handleUnexpected(
            new IllegalStateException("internal UUID 019c0f9c-6d58-7d37-b0e3-1af21f7124b9"),
            request()
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getDetail()).isEqualTo("An internal error occurred");
        assertThat(response.getBody().getProperties()).containsEntry("errorCode", "INTERNAL_SERVER_ERROR");
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/api/v1/meal-usages/example/confirmations");
    }
}
