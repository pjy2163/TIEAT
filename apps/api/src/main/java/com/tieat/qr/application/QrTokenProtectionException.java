package com.tieat.qr.application;

public final class QrTokenProtectionException extends RuntimeException {

    public QrTokenProtectionException() {
        super("QR token protection failed");
    }

    public QrTokenProtectionException(Throwable cause) {
        super("QR token protection failed", cause);
    }
}
