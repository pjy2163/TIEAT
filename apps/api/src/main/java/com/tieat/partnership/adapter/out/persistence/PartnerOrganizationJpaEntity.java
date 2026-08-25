package com.tieat.partnership.adapter.out.persistence;

import com.tieat.partnership.domain.PartnerKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

@Table(name = "partner_organizations")
@Entity
class PartnerOrganizationJpaEntity {

    @Id
    private UUID id;

    @Column(name = "display_name", nullable = false, columnDefinition = "TEXT")
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "partner_kind", nullable = false, length = 16)
    private PartnerKind partnerKind;

    @Column(name = "representative_phone", length = 30)
    private String representativePhone;

    @Column(name = "representative_email", length = 254)
    private String representativeEmail;

    protected PartnerOrganizationJpaEntity() {
    }

    PartnerOrganizationJpaEntity(UUID id, String displayName) {
        this(id, displayName, PartnerKind.ORGANIZATION);
    }

    PartnerOrganizationJpaEntity(UUID id, String displayName, PartnerKind partnerKind) {
        this(id, displayName, partnerKind, null, null);
    }

    PartnerOrganizationJpaEntity(
        UUID id,
        String displayName,
        PartnerKind partnerKind,
        String representativePhone,
        String representativeEmail
    ) {
        this.id = id;
        this.displayName = displayName;
        this.partnerKind = partnerKind;
        this.representativePhone = representativePhone;
        this.representativeEmail = representativeEmail;
    }

    UUID id() {
        return id;
    }

    String displayName() {
        return displayName;
    }

    PartnerKind partnerKind() {
        return partnerKind;
    }

    String representativePhone() {
        return representativePhone;
    }

    String representativeEmail() {
        return representativeEmail;
    }
}
