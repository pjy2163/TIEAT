package com.tieat.ledger.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ConfirmedMealUsageExportSnapshot(
    String fromDate,
    String toDate,
    String scopeLabel,
    Instant generatedAt,
    List<Row> rows,
    long totalAmountMinor
) {

    public ConfirmedMealUsageExportSnapshot {
        Objects.requireNonNull(fromDate, "From date must be supplied");
        Objects.requireNonNull(toDate, "To date must be supplied");
        Objects.requireNonNull(scopeLabel, "Scope label must be supplied");
        Objects.requireNonNull(generatedAt, "Generated at must be supplied");
        rows = List.copyOf(Objects.requireNonNull(rows, "Export rows must be supplied"));
        if (totalAmountMinor < 0) {
            throw new IllegalArgumentException("Export total must not be negative");
        }
    }

    public record Row(
        String partnerDisplayName,
        Instant usedAt,
        Instant confirmedAt,
        long amountMinor,
        String paymentStatus
    ) {

        public Row {
            Objects.requireNonNull(usedAt, "Used at must be supplied");
            Objects.requireNonNull(confirmedAt, "Confirmed at must be supplied");
            if (amountMinor <= 0) {
                throw new IllegalArgumentException("Export amount must be positive");
            }
        }
    }
}
