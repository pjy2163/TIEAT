package com.tieat.partnership.application;

public class StorePartnerNotFoundException extends RuntimeException {

    public StorePartnerNotFoundException() {
        super("Store partner was not found");
    }
}
