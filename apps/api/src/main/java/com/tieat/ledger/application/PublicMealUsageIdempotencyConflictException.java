package com.tieat.ledger.application;

public final class PublicMealUsageIdempotencyConflictException extends RuntimeException {

    public PublicMealUsageIdempotencyConflictException() {
        super("Idempotency key was already used with a different request payload");
    }
}
