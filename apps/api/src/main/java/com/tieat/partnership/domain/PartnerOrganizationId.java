package com.tieat.partnership.domain;

import java.util.Objects;
import java.util.UUID;

public record PartnerOrganizationId(UUID value) {

    public PartnerOrganizationId {
        Objects.requireNonNull(value, "Partner organization id must be supplied");
    }
}
