package com.tieat.partnership.application;

public class StoreArchivePinException extends RuntimeException {

    public enum Reason {
        NOT_CONFIGURED,
        INVALID,
        LOCKED,
        CURRENT_REQUIRED,
        ACCOUNT_PASSWORD_INVALID,
        ALREADY_CONFIGURED
    }

    private final Reason reason;

    public StoreArchivePinException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
