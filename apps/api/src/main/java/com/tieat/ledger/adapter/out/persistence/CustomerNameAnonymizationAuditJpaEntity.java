package com.tieat.ledger.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customer_name_anonymization_audits")
class CustomerNameAnonymizationAuditJpaEntity {

    @Id
    private UUID id;

    @Column(name = "cutoff_at", nullable = false)
    private Instant cutoffAt;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    @Column(name = "affected_count", nullable = false)
    private int affectedCount;

    protected CustomerNameAnonymizationAuditJpaEntity() {
    }

    CustomerNameAnonymizationAuditJpaEntity(UUID id, Instant cutoffAt, Instant executedAt, int affectedCount) {
        this.id = id;
        this.cutoffAt = cutoffAt;
        this.executedAt = executedAt;
        this.affectedCount = affectedCount;
    }
}
