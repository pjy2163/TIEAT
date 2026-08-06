package com.tieat.ledger.domain;

/**
 * The ledger allocation fixed when a meal usage is confirmed.
 *
 * <p>This is accounting data only; it does not initiate or represent a funds movement.</p>
 */
public record PrepaidAllocation(
    long usageAmount,
    long prepaidApplied,
    long receivableCreated,
    long remainingPrepaid
) {

    public PrepaidAllocation {
        if (usageAmount <= 0) {
            throw new IllegalArgumentException("Usage amount must be positive");
        }
        if (prepaidApplied < 0 || receivableCreated < 0 || remainingPrepaid < 0) {
            throw new IllegalArgumentException("Allocation values must not be negative");
        }
        if (Math.addExact(prepaidApplied, receivableCreated) != usageAmount) {
            throw new IllegalArgumentException("Prepaid and receivable allocations must equal usage amount");
        }
    }

    public static PrepaidAllocation forUsage(long usageAmount, long availablePrepaid) {
        if (usageAmount <= 0) {
            throw new IllegalArgumentException("Usage amount must be positive");
        }
        if (availablePrepaid < 0) {
            throw new IllegalArgumentException("Available prepaid must not be negative");
        }

        long prepaidApplied = Math.min(usageAmount, availablePrepaid);
        long receivableCreated = usageAmount - prepaidApplied;
        long remainingPrepaid = availablePrepaid - prepaidApplied;
        return new PrepaidAllocation(usageAmount, prepaidApplied, receivableCreated, remainingPrepaid);
    }
}
