package com.tieat.ledger.application;

public final class InvalidConfirmedMealUsageQueryException extends RuntimeException {

    public InvalidConfirmedMealUsageQueryException() {
        super("Confirmed meal usage date range query is invalid");
    }
}
