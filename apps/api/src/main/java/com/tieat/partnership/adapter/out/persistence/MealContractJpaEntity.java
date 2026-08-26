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

@Table(name = "meal_contracts")
@Entity
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

    @Column(name = "partner_organization_id")
    private UUID partnerOrganizationId;

    @Column(name = "qr_selectable", nullable = false)
    private boolean qrSelectable;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "archived_by_login_id", length = 120)
    private String archivedByLoginId;

    protected MealContractJpaEntity() {
    }

    MealContractJpaEntity(
        UUID id,
        UUID storeId,
        MealContractPaymentType paymentType,
        long prepaidBalance,
        UUID partnerOrganizationId,
        boolean qrSelectable
    ) {
        this(id, storeId, paymentType, prepaidBalance, partnerOrganizationId, qrSelectable, null, null);
    }

    MealContractJpaEntity(
        UUID id,
        UUID storeId,
        MealContractPaymentType paymentType,
        long prepaidBalance,
        UUID partnerOrganizationId,
        boolean qrSelectable,
        Instant archivedAt,
        String archivedByLoginId
    ) {
        this.id = id;
        this.storeId = storeId;
        this.paymentType = paymentType;
        this.prepaidBalance = prepaidBalance;
        this.partnerOrganizationId = partnerOrganizationId;
        this.qrSelectable = qrSelectable;
        this.archivedAt = archivedAt;
        this.archivedByLoginId = archivedByLoginId;
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

    UUID partnerOrganizationId() {
        return partnerOrganizationId;
    }

    boolean qrSelectable() {
        return qrSelectable;
    }

    Instant archivedAt() {
        return archivedAt;
    }

    String archivedByLoginId() {
        return archivedByLoginId;
    }
}
