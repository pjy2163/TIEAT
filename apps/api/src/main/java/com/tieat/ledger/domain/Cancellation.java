package com.tieat.ledger.domain;

import java.time.Instant;
import java.util.Objects;

public record Cancellation(CancellationReason reason, Instant cancelledAt) {

    public Cancellation {
        Objects.requireNonNull(reason, "Cancellation reason must be supplied");
        Objects.requireNonNull(cancelledAt, "Cancellation time must be supplied by the server");
    }
}
