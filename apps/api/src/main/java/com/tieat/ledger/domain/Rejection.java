package com.tieat.ledger.domain;

import java.time.Instant;
import java.util.Objects;

public record Rejection(String staffLoginId, Instant rejectedAt) {

    public Rejection {
        if (staffLoginId == null || staffLoginId.isBlank()) {
            throw new IllegalArgumentException("Rejecting staff login id must not be blank");
        }
        Objects.requireNonNull(rejectedAt, "Rejected at must be supplied");
    }
}
