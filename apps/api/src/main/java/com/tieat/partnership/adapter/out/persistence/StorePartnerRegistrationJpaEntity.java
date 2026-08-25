package com.tieat.partnership.adapter.out.persistence;

import com.tieat.partnership.domain.MealContractPaymentType;
import com.tieat.partnership.domain.PartnerKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "store_partner_registrations")
@IdClass(StorePartnerRegistrationJpaEntity.Key.class)
class StorePartnerRegistrationJpaEntity {

    @Id
    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Id
    @Column(name = "idempotency_key", nullable = false)
    private UUID idempotencyKey;

    @Column(name = "partner_organization_id", nullable = false)
    private UUID partnerOrganizationId;

    @Column(name = "meal_contract_id", nullable = false)
    private UUID mealContractId;

    @Column(name = "partner_display_name", nullable = false, length = 100)
    private String partnerDisplayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "partner_kind", nullable = false, length = 16)
    private PartnerKind partnerKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", nullable = false, length = 48)
    private MealContractPaymentType paymentType;

    @Column(name = "initial_prepaid_balance_minor", nullable = false)
    private long initialPrepaidBalanceMinor;

    @Column(name = "qr_selectable", nullable = false)
    private boolean qrSelectable;

    protected StorePartnerRegistrationJpaEntity() {
    }

    StorePartnerRegistrationJpaEntity(
        UUID storeId,
        UUID idempotencyKey,
        UUID partnerOrganizationId,
        UUID mealContractId,
        String partnerDisplayName,
        PartnerKind partnerKind,
        MealContractPaymentType paymentType,
        long initialPrepaidBalanceMinor,
        boolean qrSelectable
    ) {
        this.storeId = storeId;
        this.idempotencyKey = idempotencyKey;
        this.partnerOrganizationId = partnerOrganizationId;
        this.mealContractId = mealContractId;
        this.partnerDisplayName = partnerDisplayName;
        this.partnerKind = partnerKind;
        this.paymentType = paymentType;
        this.initialPrepaidBalanceMinor = initialPrepaidBalanceMinor;
        this.qrSelectable = qrSelectable;
    }

    UUID storeId() {
        return storeId;
    }

    UUID idempotencyKey() {
        return idempotencyKey;
    }

    UUID partnerOrganizationId() {
        return partnerOrganizationId;
    }

    UUID mealContractId() {
        return mealContractId;
    }

    String partnerDisplayName() {
        return partnerDisplayName;
    }

    PartnerKind partnerKind() {
        return partnerKind;
    }

    MealContractPaymentType paymentType() {
        return paymentType;
    }

    long initialPrepaidBalanceMinor() {
        return initialPrepaidBalanceMinor;
    }

    boolean qrSelectable() {
        return qrSelectable;
    }

    public static final class Key implements Serializable {

        private UUID storeId;
        private UUID idempotencyKey;

        public Key() {
        }

        public Key(UUID storeId, UUID idempotencyKey) {
            this.storeId = storeId;
            this.idempotencyKey = idempotencyKey;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(storeId, key.storeId) && Objects.equals(idempotencyKey, key.idempotencyKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(storeId, idempotencyKey);
        }
    }
}
