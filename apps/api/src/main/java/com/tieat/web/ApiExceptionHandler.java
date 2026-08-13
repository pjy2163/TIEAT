package com.tieat.web;

import com.tieat.ledger.application.MealUsageAlreadyConfirmedException;
import com.tieat.ledger.application.MealUsageNotPendingException;
import com.tieat.ledger.application.MealContractNotFoundException;
import com.tieat.ledger.application.PublicMealUsageIdempotencyConflictException;
import com.tieat.ledger.application.PublicMealUsageRateLimitExceededException;
import com.tieat.ledger.application.PublicQrMealContractNotFoundException;
import com.tieat.ledger.application.MealUsageNotFoundException;
import com.tieat.ledger.application.InvalidPendingMealUsageQueryException;
import com.tieat.ledger.application.InvalidMonthlyMealUsageQueryException;
import com.tieat.settlement.application.InvalidPosSettlementHistoryQueryException;
import com.tieat.ledger.domain.PublicMealUsageIdempotency.InvalidPublicRequestKeyException;
import com.tieat.qr.application.PublicMealUsageQrNotFoundException;
import com.tieat.settlement.application.PosSettlementConflictException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MissingRequestHeaderException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private final ProblemDetailFactory problemDetailFactory;

    public ApiExceptionHandler(ProblemDetailFactory problemDetailFactory) {
        this.problemDetailFactory = problemDetailFactory;
    }

    @ExceptionHandler(MealUsageNotFoundException.class)
    ResponseEntity<ProblemDetail> handleMealUsageNotFound(
        MealUsageNotFoundException exception,
        HttpServletRequest request
    ) {
        return problem(request, HttpStatus.NOT_FOUND, "MEAL_USAGE_NOT_FOUND", "Meal usage was not found");
    }

    @ExceptionHandler(MealContractNotFoundException.class)
    ResponseEntity<ProblemDetail> handleMealContractNotFound(
        MealContractNotFoundException exception,
        HttpServletRequest request
    ) {
        return problem(request, HttpStatus.NOT_FOUND, "MEAL_CONTRACT_NOT_FOUND", "Meal contract was not found");
    }

    @ExceptionHandler({PublicMealUsageQrNotFoundException.class, PublicQrMealContractNotFoundException.class})
    ResponseEntity<ProblemDetail> handlePublicQrNotFound(RuntimeException exception, HttpServletRequest request) {
        return problem(request, HttpStatus.NOT_FOUND, "PUBLIC_MEAL_USAGE_QR_NOT_FOUND", "Public meal usage QR was not found");
    }

    @ExceptionHandler(MealUsageAlreadyConfirmedException.class)
    ResponseEntity<ProblemDetail> handleAlreadyConfirmed(
        MealUsageAlreadyConfirmedException exception,
        HttpServletRequest request
    ) {
        return problem(request, HttpStatus.CONFLICT, "MEAL_USAGE_ALREADY_CONFIRMED", "Meal usage is already confirmed");
    }

    @ExceptionHandler(MealUsageNotPendingException.class)
    ResponseEntity<ProblemDetail> handleMealUsageNotPending(
        MealUsageNotPendingException exception,
        HttpServletRequest request
    ) {
        return problem(request, HttpStatus.CONFLICT, "MEAL_USAGE_NOT_PENDING", "Meal usage is no longer pending");
    }

    @ExceptionHandler(PublicMealUsageIdempotencyConflictException.class)
    ResponseEntity<ProblemDetail> handlePublicIdempotencyConflict(
        PublicMealUsageIdempotencyConflictException exception,
        HttpServletRequest request
    ) {
        return problem(request, HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "Idempotency key was already used with a different request payload");
    }

    @ExceptionHandler(PublicMealUsageRateLimitExceededException.class)
    ResponseEntity<ProblemDetail> handlePublicRateLimit(
        PublicMealUsageRateLimitExceededException exception,
        HttpServletRequest request
    ) {
        return problem(request, HttpStatus.TOO_MANY_REQUESTS, "PUBLIC_QR_RATE_LIMITED", "Public QR request rate limit was exceeded");
    }

    @ExceptionHandler(PosSettlementConflictException.class)
    ResponseEntity<ProblemDetail> handlePosSettlementConflict(
        PosSettlementConflictException exception,
        HttpServletRequest request
    ) {
        return switch (exception.reason()) {
            case IDEMPOTENCY_KEY_REUSED -> problem(
                request,
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_KEY_REUSED",
                "Idempotency key was already used with a different request payload"
            );
            case USAGE_NOT_OUTSTANDING -> problem(
                request,
                HttpStatus.CONFLICT,
                "POS_SETTLEMENT_USAGE_NOT_OUTSTANDING",
                "Selected meal usage is not an outstanding receivable"
            );
            case USAGE_CONTRACT_MISMATCH -> problem(
                request,
                HttpStatus.CONFLICT,
                "POS_SETTLEMENT_USAGE_CONTRACT_MISMATCH",
                "Selected meal usages must belong to the requested meal contract"
            );
            case USAGE_ALREADY_ALLOCATED -> problem(
                request,
                HttpStatus.CONFLICT,
                "POS_SETTLEMENT_USAGE_ALREADY_ALLOCATED",
                "Selected meal usage was already allocated to a POS settlement"
            );
            case TOTAL_MISMATCH -> problem(
                request,
                HttpStatus.CONFLICT,
                "POS_SETTLEMENT_TOTAL_MISMATCH",
                "Submitted POS total must equal the selected receivables"
            );
        };
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleOptimisticConflict(
        OptimisticLockingFailureException exception,
        HttpServletRequest request
    ) {
        return problem(
            request,
            HttpStatus.CONFLICT,
            "MEAL_USAGE_CONFIRMATION_CONFLICT",
            "Meal usage confirmation conflicted with a concurrent update"
        );
    }

    @ExceptionHandler({
        BindException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentNotValidException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class,
        MissingRequestHeaderException.class,
        InvalidPendingMealUsageQueryException.class,
        InvalidMonthlyMealUsageQueryException.class,
        InvalidPosSettlementHistoryQueryException.class,
        InvalidPublicRequestKeyException.class
    })
    ResponseEntity<ProblemDetail> handleValidation(Exception exception, HttpServletRequest request) {
        return problem(request, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> handleUnsupportedMediaType(Exception exception, HttpServletRequest request) {
        return problem(request, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Request content type is unsupported");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
        return problem(request, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "An internal error occurred");
    }

    private ResponseEntity<ProblemDetail> problem(
        HttpServletRequest request,
        HttpStatus status,
        String errorCode,
        String detail
    ) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
        if (problemDetailFactory.isPublicMealUsageQrRequest(request)
            || problemDetailFactory.isMonthlyMealUsageRequest(request)
            || problemDetailFactory.isPosSettlementRequest(request)) {
            response.cacheControl(CacheControl.noStore());
        }
        return response.body(problemDetailFactory.create(request, status, errorCode, detail));
    }
}
