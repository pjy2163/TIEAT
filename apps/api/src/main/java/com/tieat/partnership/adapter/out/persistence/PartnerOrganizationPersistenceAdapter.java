package com.tieat.partnership.adapter.out.persistence;

import com.tieat.partnership.domain.PartnerOrganization;
import com.tieat.partnership.domain.PartnerOrganizationId;
import com.tieat.partnership.domain.PartnerOrganizationRepository;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class PartnerOrganizationPersistenceAdapter implements PartnerOrganizationRepository {

    private final PartnerOrganizationJpaRepository repository;

    public PartnerOrganizationPersistenceAdapter(PartnerOrganizationJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Override
    public PartnerOrganization save(PartnerOrganization partnerOrganization) {
        Objects.requireNonNull(partnerOrganization, "Partner organization must be supplied");
        return toDomain(repository.saveAndFlush(new PartnerOrganizationJpaEntity(
            partnerOrganization.id().value(), partnerOrganization.displayName()
        )));
    }

    @Override
    public Optional<PartnerOrganization> findById(PartnerOrganizationId id) {
        Objects.requireNonNull(id, "Partner organization id must be supplied");
        return repository.findById(id.value()).map(this::toDomain);
    }

    private PartnerOrganization toDomain(PartnerOrganizationJpaEntity entity) {
        return new PartnerOrganization(new PartnerOrganizationId(entity.id()), entity.displayName());
    }
}
