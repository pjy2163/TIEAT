package com.tieat.ledger.application;

public final class PublicQrCreationPausedException extends RuntimeException {
    public PublicQrCreationPausedException() {
        super("Public QR creation is paused");
    }
}
