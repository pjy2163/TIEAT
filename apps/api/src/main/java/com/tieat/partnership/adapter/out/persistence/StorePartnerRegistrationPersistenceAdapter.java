package com.tieat.partnership.adapter.out.persistence;

import com.tieat.partnership.domain.MealContractId;
import com.tieat.partnership.domain.PartnerOrganizationId;
import com.tieat.partnership.domain.StorePartnerRegistration;
import com.tieat.partnership.domain.StorePartnerRegistrationRepository;
import com.tieat.store.domain.StoreId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StorePartnerRegistrationPersistenceAdapter implements StorePartnerRegistrationRepository {

    private final StorePartnerRegistrationJpaRepository repository;
    private final JdbcTemplate jdbcTemplate;

    public StorePartnerRegistrationPersistenceAdapter(
        StorePartnerRegistrationJpaRepository repository,
        JdbcTemplate jdbcTemplate
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
    }

    @Override
    public void lockStore(StoreId storeId) {
        Objects.requireNonNull(storeId, "Store id must be supplied");
        jdbcTemplate.query(
            "select pg_advisory_xact_lock(?)",
            statement -> statement.setLong(1, lockKey(storeId)),
            resultSet -> null
        );
    }

    @Override
    public Optional<StorePartnerRegistration> findByStoreIdAndIdempotencyKey(StoreId storeId, UUID idempotencyKey) {
        Objects.requireNonNull(storeId, "Store id must be supplied");
        Objects.requireNonNull(idempotencyKey, "Idempotency key must be supplied");
        return repository.findByStoreIdAndIdempotencyKey(storeId.value(), idempotencyKey).map(this::toDomain);
    }

    @Override
    public StorePartnerRegistration save(StorePartnerRegistration registration) {
        Objects.requireNonNull(registration, "Store partner registration must be supplied");
        return toDomain(repository.saveAndFlush(new StorePartnerRegistrationJpaEntity(
            registration.storeId().value(),
            registration.idempotencyKey(),
            registration.partnerOrganizationId().value(),
            registration.mealContractId().value(),
            registration.partnerDisplayName(),
            registration.partnerKind(),
            registration.paymentType(),
            registration.initialPrepaidBalanceMinor(),
            registration.qrSelectable()
        )));
    }

    private StorePartnerRegistration toDomain(StorePartnerRegistrationJpaEntity entity) {
        return new StorePartnerRegistration(
            new StoreId(entity.storeId()),
            entity.idempotencyKey(),
            new PartnerOrganizationId(entity.partnerOrganizationId()),
            new MealContractId(entity.mealContractId()),
            entity.partnerDisplayName(),
            entity.partnerKind(),
            entity.paymentType(),
            entity.initialPrepaidBalanceMinor(),
            entity.qrSelectable()
        );
    }

    private long lockKey(StoreId storeId) {
        UUID value = storeId.value();
        return value.getMostSignificantBits() ^ value.getLeastSignificantBits();
    }
}
