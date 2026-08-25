package com.tieat.partnership.domain;

import java.util.Objects;

public final class PartnerOrganization {

    private final PartnerOrganizationId id;
    private final String displayName;
    private final PartnerKind partnerKind;
    private final String representativePhone;
    private final String representativeEmail;

    public PartnerOrganization(PartnerOrganizationId id, String displayName) {
        this(id, displayName, PartnerKind.ORGANIZATION);
    }

    public PartnerOrganization(PartnerOrganizationId id, String displayName, PartnerKind partnerKind) {
        this(id, displayName, partnerKind, null, null);
    }

    public PartnerOrganization(
        PartnerOrganizationId id,
        String displayName,
        PartnerKind partnerKind,
        String representativePhone,
        String representativeEmail
    ) {
        this.id = Objects.requireNonNull(id, "Partner organization id must be supplied");
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Partner organization display name must not be blank");
        }
        this.displayName = displayName;
        this.partnerKind = Objects.requireNonNull(partnerKind, "Partner kind must be supplied");
        this.representativePhone = representativePhone;
        this.representativeEmail = representativeEmail;
    }

    public PartnerOrganizationId id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public PartnerKind partnerKind() {
        return partnerKind;
    }

    public String representativePhone() {
        return representativePhone;
    }

    public String representativeEmail() {
        return representativeEmail;
    }
}
