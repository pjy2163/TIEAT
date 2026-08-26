package com.tieat.partnership.application;

public class StorePartnerConflictException extends RuntimeException {

    public StorePartnerConflictException() {
        super("Idempotency key was already used with a different partner registration request");
    }
}
