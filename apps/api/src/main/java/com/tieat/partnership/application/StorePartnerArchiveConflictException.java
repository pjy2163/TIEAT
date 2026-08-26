package com.tieat.partnership.application;

public class StorePartnerArchiveConflictException extends RuntimeException {

    public StorePartnerArchiveConflictException() {
        super("Store partner cannot be archived while unsettled or pending activity remains");
    }
}
