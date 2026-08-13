package com.tieat.settlement.application;

public final class InvalidPosSettlementHistoryQueryException extends RuntimeException {

    public InvalidPosSettlementHistoryQueryException() {
        super("POS settlement history query is invalid");
    }
}
