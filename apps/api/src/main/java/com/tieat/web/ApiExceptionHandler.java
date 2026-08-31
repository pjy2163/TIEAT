package com.tieat.web;

import com.tieat.ledger.application.MealUsageAlreadyConfirmedException;
import com.tieat.ledger.application.MealUsageNotPendingException;
import com.tieat.ledger.application.MealContractNotFoundException;
import com.tieat.ledger.application.PublicMealUsageIdempotencyConflictException;
import com.tieat.ledger.application.PublicMealUsageRateLimitExceededException;
import com.tieat.ledger.application.MealUsagePendingLimitReachedException;
import com.tieat.ledger.application.PublicQrMealContractNotFoundException;
import com.tieat.ledger.application.MealUsageNotFoundException;
import com.tieat.ledger.application.InvalidPendingMealUsageQueryException;
import com.tieat.ledger.application.InvalidMonthlyMealUsageQueryException;
import com.tieat.ledger.application.InvalidConfirmedMealUsageQueryException;
import com.tieat.ledger.application.ConfirmedMealUsageExportTooLargeException;
import com.tieat.onboarding.application.OnboardingException;
import com.tieat.identity.application.SessionReauthenticationFailedException;
import com.tieat.identity.application.SessionReauthenticationInvalidException;
import com.tieat.identity.application.PasswordReauthenticationRequiredException;
import com.tieat.settlement.application.InvalidPosSettlementHistoryQueryException;
import com.tieat.ledger.domain.PublicMealUsageIdempotency.InvalidPublicRequestKeyException;
import com.tieat.qr.application.PublicMealUsageQrNotFoundException;
import com.tieat.qr.application.StoreMealUsageQrRenewalConflictException;
import com.tieat.settlement.application.PosSettlementConflictException;
import com.tieat.partnership.application.StorePartnerConflictException;
import com.tieat.partnership.application.StorePartnerArchiveConflictException;
import com.tieat.partnership.application.StorePartnerArchivePinRequiredException;
import com.tieat.partnership.application.StorePartnerNotFoundException;
import com.tieat.partnership.application.StorePartnerPaymentTermConflictException;
import com.tieat.partnership.application.StorePartnerValidationException;
import com.tieat.partnership.application.StoreArchivePinException;
import com.tieat.security.application.RateLimitExceededException;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final ProblemDetailFactory problemDetailFactory;

    public ApiExceptionHandler(ProblemDetailFactory problemDetailFactory) {
        this.problemDetailFactory = problemDetailFactory;
    }

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<ProblemDetail> handleRateLimit(RateLimitExceededException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", Long.toString(exception.retryAfter().toSeconds()))
            .cacheControl(CacheControl.noStore())
            .body(problemDetailFactory.create(
                request, HttpStatus.TOO_MANY_REQUESTS, "REQUEST_RATE_LIMITED", "Too many requests"
            ));
    }

    @ExceptionHandler(OnboardingException.class)
    ResponseEntity<ProblemDetail> handleOnboarding(
        OnboardingException exception,
        HttpServletRequest request
    ) {
        return switch (exception.reason()) {
            case INVITE_INVALID -> problem(
                request,
                HttpStatus.FORBIDDEN,
                "ONBOARDING_INVITE_INVALID",
                "Invitation code is invalid"
            );
            case VALIDATION_FAILED -> problem(
                request,
                HttpStatus.BAD_REQUEST,
                "ONBOARDING_VALIDATION_FAILED",
                "Onboarding input is invalid"
            );
            case LOGIN_ID_ALREADY_IN_USE -> problem(
                request,
                HttpStatus.CONFLICT,
                "ONBOARDING_LOGIN_ID_IN_USE",
                "An account may already exist. Sign in instead"
            );
            case PLACE_SEARCH_INVALID -> problem(
                request,
                HttpStatus.BAD_REQUEST,
                "STORE_PLACE_SEARCH_INVALID",
                "Store place search input is invalid"
            );
            case PLACE_SEARCH_UNAVAILABLE -> problem(
                request,
                HttpStatus.SERVICE_UNAVAILABLE,
                "STORE_PLACE_SEARCH_UNAVAILABLE",
                "Store place search is temporarily unavailable"
            );
        };
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

    @ExceptionHandler(StorePartnerNotFoundException.class)
    ResponseEntity<ProblemDetail> handleStorePartnerNotFound(
        StorePartnerNotFoundException exception,
        HttpServletRequest request
    ) {
        return problem(request, HttpStatus.NOT_FOUND, "STORE_PARTNER_NOT_FOUND", "Store partner was not found");
    }

    @ExceptionHandler(StorePartnerConflictException.class)
    ResponseEntity<ProblemDetail> handleStorePartnerConflict(
        StorePartnerConflictException exception,
        HttpServletRequest request
    ) {
        return problem(
            request,
            HttpStatus.CONFLICT,
            "IDEMPOTENCY_KEY_REUSED",
            "Idempotency key was already used with a different request payload"
        );
    }

    @ExceptionHandler(StorePartnerArchiveConflictException.class)
    ResponseEntity<ProblemDetail> handleStorePartnerArchiveConflict(
        StorePartnerArchiveConflictException exception,
        HttpServletRequest request
    ) {
        return problem(
            request,
            HttpStatus.CONFLICT,
            "STORE_PARTNER_ARCHIVE_BLOCKED",
            "Partner has pending usage, unsettled receivables, or remaining prepaid balance"
        );
    }

    @ExceptionHandler(StorePartnerPaymentTermConflictException.class)
    ResponseEntity<ProblemDetail> handleStorePartnerPaymentTermConflict(
        StorePartnerPaymentTermConflictException exception,
        HttpServletRequest request
    ) {
        return switch (exception.reason()) {
            case EXPECTED_PAYMENT_TYPE_STALE -> problem(
                request,
                HttpStatus.CONFLICT,
                "STORE_PARTNER_PAYMENT_TERM_STALE",
                "The partner payment type changed before this request was applied"
            );
            case PENDING_USAGE -> problem(
                request,
                HttpStatus.CONFLICT,
                "STORE_PARTNER_PAYMENT_TERM_BLOCKED",
                "Payment type cannot change while pending usage remains"
            );
            case OUTSTANDING_RECEIVABLE -> problem(
                request,
                HttpStatus.CONFLICT,
                "STORE_PARTNER_PAYMENT_TERM_BLOCKED",
                "Payment type cannot change while unsettled receivables remain"
            );
            case PREPAID_BALANCE_REMAINING -> problem(
                request,
                HttpStatus.CONFLICT,
                "STORE_PARTNER_PAYMENT_TERM_BLOCKED",
                "Payment type cannot change while prepaid balance remains"
            );
        };
    }

    @ExceptionHandler(StoreArchivePinException.class)
    ResponseEntity<ProblemDetail> handleStoreArchivePin(
        StoreArchivePinException exception,
        HttpServletRequest request
    ) {
        return switch (exception.reason()) {
            case NOT_CONFIGURED -> problem(
                request,
                HttpStatus.CONFLICT,
                "STORE_ARCHIVE_PIN_NOT_CONFIGURED",
                "Configure the store archive PIN before archiving a partner"
            );
            case INVALID -> problem(
                request,
                HttpStatus.FORBIDDEN,
                "STORE_ARCHIVE_PIN_INVALID",
                "The archive PIN is incorrect"
            );
            case LOCKED -> problem(
                request,
                HttpStatus.TOO_MANY_REQUESTS,
                "STORE_ARCHIVE_PIN_LOCKED",
                "Archive PIN verification is temporarily locked"
            );
            case CURRENT_REQUIRED -> problem(
                request,
                HttpStatus.BAD_REQUEST,
                "STORE_ARCHIVE_PIN_CURRENT_REQUIRED",
                "The current archive PIN is required to change it"
            );
            case ACCOUNT_PASSWORD_INVALID -> problem(
                request,
                HttpStatus.FORBIDDEN,
                "STORE_ARCHIVE_ACCOUNT_PASSWORD_INVALID",
                "The account password could not be verified"
            );
            case ALREADY_CONFIGURED -> problem(
                request,
                HttpStatus.CONFLICT,
                "STORE_ARCHIVE_PIN_ALREADY_CONFIGURED",
                "The store archive PIN is already configured"
            );
        };
    }

    @ExceptionHandler(PasswordReauthenticationRequiredException.class)
    ResponseEntity<ProblemDetail> handlePasswordReauthenticationRequired(
        PasswordReauthenticationRequiredException exception,
        HttpServletRequest request
    ) {
        return problem(
            request,
            HttpStatus.FORBIDDEN,
            "PASSWORD_REAUTHENTICATION_REQUIRED",
            "Password reauthentication is required"
        );
    }

    @ExceptionHandler(StorePartnerArchivePinRequiredException.class)
    ResponseEntity<ProblemDetail> handleStorePartnerArchivePinRequired(
        StorePartnerArchivePinRequiredException exception,
        HttpServletRequest request
    ) {
        return problem(
            request,
            HttpStatus.FORBIDDEN,
            "STORE_PARTNER_ARCHIVE_PIN_REQUIRED",
            "Use the PIN-verified archive operation"
        );
    }

    @ExceptionHandler(StorePartnerValidationException.class)
    ResponseEntity<ProblemDetail> handleStorePartnerValidation(
        StorePartnerValidationException exception,
        HttpServletRequest request
    ) {
        return problem(request, HttpStatus.BAD_REQUEST, "STORE_PARTNER_VALIDATION_FAILED", "Store partner input is invalid");
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

    @ExceptionHandler(MealUsagePendingLimitReachedException.class)
    ResponseEntity<ProblemDetail> handlePendingLimit(
        MealUsagePendingLimitReachedException exception,
        HttpServletRequest request
    ) {
        return problem(
            request,
            HttpStatus.TOO_MANY_REQUESTS,
            "MEAL_USAGE_PENDING_LIMIT_REACHED",
            "Store pending meal usage capacity has been reached"
        );
    }

    @ExceptionHandler(StoreMealUsageQrRenewalConflictException.class)
    ResponseEntity<ProblemDetail> handleStoreMealUsageQrRenewalConflict(
        StoreMealUsageQrRenewalConflictException exception,
        HttpServletRequest request
    ) {
        return problem(
            request,
            HttpStatus.CONFLICT,
            "STORE_MEAL_USAGE_QR_RENEWAL_NOT_ALLOWED",
            "Store meal usage QR renewal is not available"
        );
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

    @ExceptionHandler(SessionReauthenticationFailedException.class)
    ResponseEntity<ProblemDetail> handleSessionReauthenticationFailed(
        SessionReauthenticationFailedException exception,
        HttpServletRequest request
    ) {
        return problem(
            request,
            HttpStatus.UNAUTHORIZED,
            "SESSION_REAUTHENTICATION_FAILED",
            "Reauthentication failed"
        );
    }

    @ExceptionHandler(SessionReauthenticationInvalidException.class)
    ResponseEntity<ProblemDetail> handleSessionReauthenticationInvalid(
        SessionReauthenticationInvalidException exception,
        HttpServletRequest request
    ) {
        return problem(
            request,
            HttpStatus.BAD_REQUEST,
            "SESSION_REAUTHENTICATION_INVALID",
            "Reauthentication input is invalid"
        );
    }

    @ExceptionHandler(ConfirmedMealUsageExportTooLargeException.class)
    ResponseEntity<ProblemDetail> handleConfirmedMealUsageExportTooLarge(
        ConfirmedMealUsageExportTooLargeException exception,
        HttpServletRequest request
    ) {
        return problem(
            request,
            HttpStatus.PAYLOAD_TOO_LARGE,
            "CONFIRMED_MEAL_USAGE_EXPORT_TOO_LARGE",
            "Confirmed meal usage export exceeds 10000 rows"
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
        InvalidConfirmedMealUsageQueryException.class,
        InvalidPosSettlementHistoryQueryException.class,
        InvalidPublicRequestKeyException.class
    })
    ResponseEntity<ProblemDetail> handleValidation(Exception exception, HttpServletRequest request) {
        if (exception instanceof HttpMessageNotReadableException
            && problemDetailFactory.isSessionReauthenticationRequest(request)) {
            return problem(
                request,
                HttpStatus.BAD_REQUEST,
                "SESSION_REAUTHENTICATION_INVALID",
                "Reauthentication input is invalid"
            );
        }
        return problem(request, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> handleUnsupportedMediaType(Exception exception, HttpServletRequest request) {
        return problem(request, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Request content type is unsupported");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error(
            "operational_event=api_error exceptionType={}",
            exception.getClass().getName()
        );
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
            || problemDetailFactory.isConfirmedMealUsageRequest(request)
            || problemDetailFactory.isPosSettlementRequest(request)
            || problemDetailFactory.isStoreOnboardingRequest(request)
            || problemDetailFactory.isStoreMealUsageQrRequest(request)
            || problemDetailFactory.isMealUsageCreationRequest(request)
            || problemDetailFactory.isSessionReauthenticationRequest(request)
            || problemDetailFactory.isSessionRequest(request)) {
            response.cacheControl(CacheControl.noStore());
        }
        return response.body(problemDetailFactory.create(request, status, errorCode, detail));
    }
}
