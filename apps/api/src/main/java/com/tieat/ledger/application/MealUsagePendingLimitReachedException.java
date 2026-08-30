package com.tieat.ledger.application;

public final class MealUsagePendingLimitReachedException extends RuntimeException {

    public MealUsagePendingLimitReachedException() {
        super("Store pending meal usage capacity has been reached");
    }
}
