package com.tieat.ledger.application;

public final class PublicMealUsageRateLimitExceededException extends RuntimeException {

    public PublicMealUsageRateLimitExceededException() {
        super("Public QR request rate limit was exceeded");
    }
}
