package com.tieat.partnership.domain;

import com.tieat.store.domain.StoreId;
import java.util.Objects;

public final class MealContract {

    private final MealContractId id;
    private final StoreId storeId;
    private final MealContractPaymentType paymentType;
    private long prepaidBalance;

    public MealContract(
        MealContractId id,
        StoreId storeId,
        MealContractPaymentType paymentType,
        long prepaidBalance
    ) {
        this.id = Objects.requireNonNull(id, "Meal contract id must be supplied");
        this.storeId = Objects.requireNonNull(storeId, "Store id must be supplied");
        this.paymentType = Objects.requireNonNull(paymentType, "Payment type must be supplied");
        if (prepaidBalance < 0) {
            throw new IllegalArgumentException("Prepaid balance must not be negative");
        }
        if (paymentType == MealContractPaymentType.POSTPAID && prepaidBalance != 0) {
            throw new IllegalArgumentException("Postpaid contracts must have zero prepaid balance");
        }
        this.prepaidBalance = prepaidBalance;
    }

    public MealContractAllocation allocate(long usageAmount) {
        if (usageAmount <= 0) {
            throw new IllegalArgumentException("Usage amount must be positive");
        }

        long prepaidApplied = paymentType == MealContractPaymentType.POSTPAID
            ? 0
            : Math.min(prepaidBalance, usageAmount);
        long receivableCreated = usageAmount - prepaidApplied;
        prepaidBalance -= prepaidApplied;
        return new MealContractAllocation(usageAmount, prepaidApplied, receivableCreated, prepaidBalance);
    }

    public MealContractId id() {
        return id;
    }

    public StoreId storeId() {
        return storeId;
    }

    public MealContractPaymentType paymentType() {
        return paymentType;
    }

    public long prepaidBalance() {
        return prepaidBalance;
    }
}
