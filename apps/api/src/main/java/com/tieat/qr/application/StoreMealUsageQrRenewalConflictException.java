package com.tieat.qr.application;

public final class StoreMealUsageQrRenewalConflictException extends RuntimeException {

    public StoreMealUsageQrRenewalConflictException() {
        super("Store meal usage QR renewal is not available");
    }
}
