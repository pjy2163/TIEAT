package com.tieat.partnership.adapter.out.persistence;

import com.tieat.partnership.domain.MealContractPaymentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "meal_contracts")
class MealContractJpaEntity {

    @Id
    private UUID id;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", nullable = false, length = 48)
    private MealContractPaymentType paymentType;

    @Column(name = "prepaid_balance", nullable = false)
    private long prepaidBalance;

    protected MealContractJpaEntity() {
    }

    MealContractJpaEntity(
        UUID id,
        UUID storeId,
        MealContractPaymentType paymentType,
        long prepaidBalance
    ) {
        this.id = id;
        this.storeId = storeId;
        this.paymentType = paymentType;
        this.prepaidBalance = prepaidBalance;
    }

    UUID id() {
        return id;
    }

    UUID storeId() {
        return storeId;
    }

    MealContractPaymentType paymentType() {
        return paymentType;
    }

    long prepaidBalance() {
        return prepaidBalance;
    }
}
