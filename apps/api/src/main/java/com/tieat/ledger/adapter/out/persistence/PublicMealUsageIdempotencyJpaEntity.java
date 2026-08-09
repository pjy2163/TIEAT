package com.tieat.ledger.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "public_meal_usage_idempotency_keys")
@IdClass(PublicMealUsageIdempotencyJpaEntity.Key.class)
class PublicMealUsageIdempotencyJpaEntity {

    @Id
    @Column(name = "qr_context_id", nullable = false)
    private UUID qrContextId;

    @Id
    @Column(name = "idempotency_key", nullable = false)
    private UUID idempotencyKey;

    @Column(name = "meal_contract_id", nullable = false)
    private UUID mealContractId;

    @Column(nullable = false)
    private long amount;

    @Column(name = "meal_usage_id", nullable = false)
    private UUID mealUsageId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PublicMealUsageIdempotencyJpaEntity() {
    }

    PublicMealUsageIdempotencyJpaEntity(
        UUID qrContextId,
        UUID idempotencyKey,
        UUID mealContractId,
        long amount,
        UUID mealUsageId,
        Instant createdAt
    ) {
        this.qrContextId = qrContextId;
        this.idempotencyKey = idempotencyKey;
        this.mealContractId = mealContractId;
        this.amount = amount;
        this.mealUsageId = mealUsageId;
        this.createdAt = createdAt;
    }

    UUID qrContextId() {
        return qrContextId;
    }

    UUID idempotencyKey() {
        return idempotencyKey;
    }

    UUID mealContractId() {
        return mealContractId;
    }

    long amount() {
        return amount;
    }

    UUID mealUsageId() {
        return mealUsageId;
    }

    public static final class Key implements Serializable {

        private UUID qrContextId;
        private UUID idempotencyKey;

        public Key() {
        }

        public Key(UUID qrContextId, UUID idempotencyKey) {
            this.qrContextId = qrContextId;
            this.idempotencyKey = idempotencyKey;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key key)) return false;
            return Objects.equals(qrContextId, key.qrContextId) && Objects.equals(idempotencyKey, key.idempotencyKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(qrContextId, idempotencyKey);
        }
    }
}
