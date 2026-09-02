package com.tieat.ledger.application;

import java.time.Duration;

public final class PublicMealUsageRateLimitExceededException extends RuntimeException {

    public PublicMealUsageRateLimitExceededException() {
        this(Duration.ofSeconds(60));
    }

    public PublicMealUsageRateLimitExceededException(Duration retryAfter) {
        super("Public QR request rate limit was exceeded");
        this.retryAfter = retryAfter;
    }

    private final Duration retryAfter;

    public Duration retryAfter() { return retryAfter; }
}
