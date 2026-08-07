package com.tieat.web;

import com.tieat.ledger.application.MealUsageAlreadyConfirmedException;
import com.tieat.ledger.application.MealContractNotFoundException;
import com.tieat.ledger.application.MealUsageNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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

    @ExceptionHandler(MealUsageAlreadyConfirmedException.class)
    ResponseEntity<ProblemDetail> handleAlreadyConfirmed(
        MealUsageAlreadyConfirmedException exception,
        HttpServletRequest request
    ) {
        return problem(request, HttpStatus.CONFLICT, "MEAL_USAGE_ALREADY_CONFIRMED", "Meal usage is already confirmed");
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
        MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<ProblemDetail> handleValidation(Exception exception, HttpServletRequest request) {
        return problem(request, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed");
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
        return ResponseEntity.status(status).body(problemDetailFactory.create(request, status, errorCode, detail));
    }
}
