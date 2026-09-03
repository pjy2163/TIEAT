package com.tieat.web;

import com.tieat.settlement.receipt.application.PosSettlementReceiptExceptions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice(assignableTypes = PosSettlementReceiptController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class PosSettlementReceiptExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PosSettlementReceiptExceptionHandler.class);

    private final ProblemDetailFactory problemDetailFactory;

    PosSettlementReceiptExceptionHandler(ProblemDetailFactory problemDetailFactory) {
        this.problemDetailFactory = problemDetailFactory;
    }

    @ExceptionHandler(PosSettlementReceiptExceptions.NotFound.class)
    ResponseEntity<ProblemDetail> handleNotFound(
        PosSettlementReceiptExceptions.NotFound exception,
        HttpServletRequest request
    ) {
        return problem(request, HttpStatus.NOT_FOUND, "POS_SETTLEMENT_RECEIPT_NOT_FOUND", "POS settlement receipt was not found");
    }

    @ExceptionHandler(PosSettlementReceiptExceptions.AlreadyAttached.class)
    ResponseEntity<ProblemDetail> handleAlreadyAttached(
        PosSettlementReceiptExceptions.AlreadyAttached exception,
        HttpServletRequest request
    ) {
        return problem(request, HttpStatus.CONFLICT, "POS_SETTLEMENT_RECEIPT_ALREADY_ATTACHED", "POS settlement already has a receipt");
    }

    @ExceptionHandler(PosSettlementReceiptExceptions.Validation.class)
    ResponseEntity<ProblemDetail> handleValidation(
        PosSettlementReceiptExceptions.Validation exception,
        HttpServletRequest request
    ) {
        return problem(request, HttpStatus.BAD_REQUEST, "POS_SETTLEMENT_RECEIPT_INVALID", exception.getMessage());
    }

    @ExceptionHandler(PosSettlementReceiptExceptions.StorageFailure.class)
    ResponseEntity<ProblemDetail> handleStorageFailure(
        PosSettlementReceiptExceptions.StorageFailure exception,
        HttpServletRequest request
    ) {
        log.error(
            "operational_event=receipt_storage_failed exceptionType={}",
            exception.getClass().getName()
        );
        return problem(request, HttpStatus.INTERNAL_SERVER_ERROR, "POS_SETTLEMENT_RECEIPT_STORAGE_FAILED", "Receipt storage is temporarily unavailable");
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<ProblemDetail> handleMissingPart(
        MissingServletRequestPartException exception,
        HttpServletRequest request
    ) {
        return problem(request, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed");
    }

    private ResponseEntity<ProblemDetail> problem(
        HttpServletRequest request,
        HttpStatus status,
        String errorCode,
        String detail
    ) {
        return ResponseEntity.status(status)
            .cacheControl(CacheControl.noStore())
            .body(problemDetailFactory.create(request, status, errorCode, detail));
    }
}
