package com.tieat.partnership.adapter.out.persistence;

import com.tieat.partnership.domain.MealContractPaymentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "meal_contract_payment_term_audits")
class MealContractPaymentTermAuditJpaEntity {

    @Id
    private UUID id;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "meal_contract_id", nullable = false)
    private UUID mealContractId;

    @Column(name = "actor_login_id", nullable = false, length = 120)
    private String actorLoginId;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_payment_type", nullable = false, length = 48)
    private MealContractPaymentType previousPaymentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_payment_type", nullable = false, length = 48)
    private MealContractPaymentType newPaymentType;

    @Column(name = "prepaid_balance_before", nullable = false)
    private long prepaidBalanceBefore;

    @Column(name = "prepaid_balance_after", nullable = false)
    private long prepaidBalanceAfter;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected MealContractPaymentTermAuditJpaEntity() {
    }

    MealContractPaymentTermAuditJpaEntity(
        UUID id,
        UUID storeId,
        UUID mealContractId,
        String actorLoginId,
        MealContractPaymentType previousPaymentType,
        MealContractPaymentType newPaymentType,
        long prepaidBalanceBefore,
        long prepaidBalanceAfter,
        Instant occurredAt
    ) {
        this.id = id;
        this.storeId = storeId;
        this.mealContractId = mealContractId;
        this.actorLoginId = actorLoginId;
        this.previousPaymentType = previousPaymentType;
        this.newPaymentType = newPaymentType;
        this.prepaidBalanceBefore = prepaidBalanceBefore;
        this.prepaidBalanceAfter = prepaidBalanceAfter;
        this.occurredAt = occurredAt;
    }
}
