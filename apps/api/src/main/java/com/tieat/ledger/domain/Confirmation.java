package com.tieat.ledger.domain;

import java.time.Instant;
import java.util.Objects;

public record Confirmation(String staffInitials, Instant confirmedAt) {

    public Confirmation {
        if (staffInitials == null || staffInitials.isBlank()) {
            throw new IllegalArgumentException("Staff initials must not be blank");
        }
        Objects.requireNonNull(confirmedAt, "Confirmed at must be supplied by the server");
    }
}
