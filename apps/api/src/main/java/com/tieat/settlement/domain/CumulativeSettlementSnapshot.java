package com.tieat.settlement.domain;

import com.tieat.partnership.domain.MealContractId;
import java.time.Instant;
import java.util.Objects;

public record CumulativeSettlementSnapshot(
    MealContractId mealContractId,
    Instant generatedAt,
    long confirmedUsageTotalMinor,
    long prepaidAppliedTotalMinor,
    long prepaidBalanceMinor,
    long receivableCreatedTotalMinor,
    long recordedPosPaymentTotalMinor,
    long allocatedReceivableTotalMinor
) {

    public CumulativeSettlementSnapshot {
        Objects.requireNonNull(mealContractId, "Meal contract id must be supplied");
        Objects.requireNonNull(generatedAt, "Generated at must be supplied");
        if (confirmedUsageTotalMinor < 0
            || prepaidAppliedTotalMinor < 0
            || prepaidBalanceMinor < 0
            || receivableCreatedTotalMinor < 0
            || recordedPosPaymentTotalMinor < 0
            || allocatedReceivableTotalMinor < 0) {
            throw new IllegalArgumentException("Settlement amounts must not be negative");
        }
        if (allocatedReceivableTotalMinor > receivableCreatedTotalMinor) {
            throw new IllegalArgumentException("Allocated receivables must not exceed created receivables");
        }
    }

    public long outstandingReceivableTotalMinor() {
        return receivableCreatedTotalMinor - allocatedReceivableTotalMinor;
    }
}
