package com.tieat.partnership.application;

public class StorePartnerPaymentTermConflictException extends RuntimeException {

    public enum Reason {
        EXPECTED_PAYMENT_TYPE_STALE,
        PENDING_USAGE,
        OUTSTANDING_RECEIVABLE,
        PREPAID_BALANCE_REMAINING
    }

    private final Reason reason;

    public StorePartnerPaymentTermConflictException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
