package com.tieat.partnership.domain;

import java.util.Objects;

public final class PartnerOrganization {

    private final PartnerOrganizationId id;
    private final String displayName;

    public PartnerOrganization(PartnerOrganizationId id, String displayName) {
        this.id = Objects.requireNonNull(id, "Partner organization id must be supplied");
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Partner organization display name must not be blank");
        }
        this.displayName = displayName;
    }

    public PartnerOrganizationId id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }
}
