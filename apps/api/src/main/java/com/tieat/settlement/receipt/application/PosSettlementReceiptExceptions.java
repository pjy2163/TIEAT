package com.tieat.settlement.receipt.application;

import java.util.UUID;

public final class PosSettlementReceiptExceptions {

    private PosSettlementReceiptExceptions() {
    }

    public static final class NotFound extends RuntimeException {
        public NotFound(UUID posSettlementId) {
            super("POS settlement receipt was not found for " + posSettlementId);
        }
    }

    public static final class AlreadyAttached extends RuntimeException {
        public AlreadyAttached(UUID posSettlementId) {
            super("POS settlement already has a receipt: " + posSettlementId);
        }
    }

    public static final class Validation extends RuntimeException {
        private final Reason reason;

        public Validation(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }

        public enum Reason {
            EMPTY_FILE,
            FILE_TOO_LARGE,
            UNSUPPORTED_MEDIA_TYPE,
            CONTENT_SIGNATURE_MISMATCH,
            FILE_NAME_MISMATCH,
            INVALID_IMAGE,
            IMAGE_DIMENSIONS_TOO_LARGE
        }
    }

    public static final class StorageFailure extends RuntimeException {
        public StorageFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
