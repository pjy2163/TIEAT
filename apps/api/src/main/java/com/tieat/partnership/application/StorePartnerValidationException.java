package com.tieat.partnership.application;

public class StorePartnerValidationException extends RuntimeException {

    public StorePartnerValidationException() {
        super("Store partner input is invalid");
    }
}
