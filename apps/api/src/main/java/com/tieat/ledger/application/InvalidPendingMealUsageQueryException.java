package com.tieat.ledger.application;

public final class InvalidPendingMealUsageQueryException extends RuntimeException {

    public InvalidPendingMealUsageQueryException() {
        super("Pending meal usage query is invalid");
    }
}
