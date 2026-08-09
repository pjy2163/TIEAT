package com.tieat.partnership.adapter.out.persistence;

import com.tieat.partnership.domain.MealContractPaymentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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

    @Column(name = "partner_organization_id")
    private UUID partnerOrganizationId;

    @Column(name = "qr_selectable", nullable = false)
    private boolean qrSelectable;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "partner_organization_id", insertable = false, updatable = false)
    private PartnerOrganizationJpaEntity partnerOrganization;

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
        this.id = id;
        this.storeId = storeId;
        this.paymentType = paymentType;
        this.prepaidBalance = prepaidBalance;
        this.partnerOrganizationId = partnerOrganizationId;
        this.qrSelectable = qrSelectable;
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

    String partnerDisplayName() {
        return partnerOrganization == null ? null : partnerOrganization.displayName();
    }
}
