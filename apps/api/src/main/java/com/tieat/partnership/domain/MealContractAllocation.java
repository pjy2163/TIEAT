package com.tieat.partnership.domain;

public record MealContractAllocation(
    long usageAmount,
    long prepaidApplied,
    long receivableCreated,
    long remainingPrepaid
) {

    public MealContractAllocation {
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
}
