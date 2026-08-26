package com.tieat.ledger.application;

public final class ConfirmedMealUsageWorkbookGenerationException extends RuntimeException {

    public ConfirmedMealUsageWorkbookGenerationException(Throwable cause) {
        super("Confirmed meal usage workbook generation failed", cause);
    }
}
