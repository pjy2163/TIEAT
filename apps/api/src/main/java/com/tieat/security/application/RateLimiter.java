package com.tieat.security.application;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

public interface RateLimiter {

    Decision check(List<Key> keys);

    Decision recordFailure(List<Key> keys);

    void clear(List<Key> keys);

    record Key(String scope, String value) {
        public Key {
            if (scope == null || scope.isBlank()) {
                throw new IllegalArgumentException("Rate limit scope must not be blank");
            }
            value = value == null || value.isBlank() ? "<unknown>" : value;
            Objects.requireNonNull(value);
        }
    }

    record Decision(boolean allowed, Duration retryAfter) {
        public static Decision permitted() {
            return new Decision(true, Duration.ZERO);
        }

        public static Decision limited(Duration retryAfter) {
            return new Decision(false, retryAfter);
        }
    }
}
