package com.tieat.qr.application;

public final class PublicMealUsageQrNotFoundException extends RuntimeException {

    public PublicMealUsageQrNotFoundException() {
        super("Public meal usage QR was not found");
    }
}
