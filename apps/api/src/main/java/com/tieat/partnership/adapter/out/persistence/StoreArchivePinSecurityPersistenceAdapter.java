package com.tieat.partnership.adapter.out.persistence;

import com.tieat.partnership.domain.StoreArchivePinSecurity;
import com.tieat.partnership.domain.StoreArchivePinSecurityRepository;
import com.tieat.store.domain.StoreId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StoreArchivePinSecurityPersistenceAdapter implements StoreArchivePinSecurityRepository {

    private final StoreArchivePinSecurityJpaRepository repository;
    private final JdbcTemplate jdbcTemplate;

    public StoreArchivePinSecurityPersistenceAdapter(
        StoreArchivePinSecurityJpaRepository repository,
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
    public Optional<StoreArchivePinSecurity> findByStoreId(StoreId storeId) {
        Objects.requireNonNull(storeId, "Store id must be supplied");
        return repository.findByStoreId(storeId.value()).map(this::toDomain);
    }

    @Override
    public Optional<StoreArchivePinSecurity> findByStoreIdForUpdate(StoreId storeId) {
        Objects.requireNonNull(storeId, "Store id must be supplied");
        return repository.findByStoreIdForUpdate(storeId.value()).map(this::toDomain);
    }

    @Override
    public StoreArchivePinSecurity save(StoreArchivePinSecurity security) {
        Objects.requireNonNull(security, "Archive PIN security must be supplied");
        repository.saveAndFlush(new StoreArchivePinSecurityJpaEntity(
            security.storeId().value(),
            security.pinHash(),
            security.failedAttempts(),
            security.failureWindowStartedAt(),
            security.lockedUntil(),
            security.updatedAt(),
            security.updatedByLoginId()
        ));
        return security;
    }

    private StoreArchivePinSecurity toDomain(StoreArchivePinSecurityJpaEntity entity) {
        return new StoreArchivePinSecurity(
            new StoreId(entity.storeId()),
            entity.pinHash(),
            entity.failedAttempts(),
            entity.failureWindowStartedAt(),
            entity.lockedUntil(),
            entity.updatedAt(),
            entity.updatedByLoginId()
        );
    }

    private long lockKey(StoreId storeId) {
        UUID value = storeId.value();
        return value.getMostSignificantBits() ^ value.getLeastSignificantBits();
    }
}
