package com.tieat.ledger.application;

public final class InvalidMonthlyMealUsageQueryException extends RuntimeException {

    public InvalidMonthlyMealUsageQueryException() {
        super("Monthly meal usage query is invalid");
    }
}
