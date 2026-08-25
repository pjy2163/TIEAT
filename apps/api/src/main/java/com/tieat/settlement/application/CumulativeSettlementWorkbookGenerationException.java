package com.tieat.settlement.application;

public final class CumulativeSettlementWorkbookGenerationException extends RuntimeException {

    public CumulativeSettlementWorkbookGenerationException(Throwable cause) {
        super("Cumulative settlement workbook generation failed", cause);
    }
}
