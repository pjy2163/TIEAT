package com.tieat.security.application;

import java.time.Duration;

public final class RateLimitExceededException extends RuntimeException {

    private final Duration retryAfter;

    public RateLimitExceededException(Duration retryAfter) {
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
