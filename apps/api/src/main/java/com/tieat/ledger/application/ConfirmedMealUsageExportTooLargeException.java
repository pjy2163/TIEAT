package com.tieat.ledger.application;

public final class ConfirmedMealUsageExportTooLargeException extends RuntimeException {

    public ConfirmedMealUsageExportTooLargeException() {
        super("Confirmed meal usage export exceeds 10000 rows");
    }
}
