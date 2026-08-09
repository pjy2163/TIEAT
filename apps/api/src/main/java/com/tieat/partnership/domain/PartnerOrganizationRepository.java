package com.tieat.partnership.domain;

import java.util.Optional;

public interface PartnerOrganizationRepository {

    PartnerOrganization save(PartnerOrganization partnerOrganization);

    Optional<PartnerOrganization> findById(PartnerOrganizationId id);
}
