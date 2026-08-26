package com.tieat.settlement.application;

public final class PosSettlementConflictException extends RuntimeException {

    private final Reason reason;

    public PosSettlementConflictException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        IDEMPOTENCY_KEY_REUSED,
        USAGE_NOT_OUTSTANDING,
        USAGE_CONTRACT_MISMATCH,
        USAGE_ALREADY_ALLOCATED,
        TOTAL_MISMATCH
    }
}
