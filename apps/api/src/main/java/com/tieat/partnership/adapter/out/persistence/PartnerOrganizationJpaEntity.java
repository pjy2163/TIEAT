package com.tieat.partnership.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "partner_organizations")
class PartnerOrganizationJpaEntity {

    @Id
    private UUID id;

    @Column(name = "display_name", nullable = false, columnDefinition = "TEXT")
    private String displayName;

    protected PartnerOrganizationJpaEntity() {
    }

    PartnerOrganizationJpaEntity(UUID id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    UUID id() {
        return id;
    }

    String displayName() {
        return displayName;
    }
}
