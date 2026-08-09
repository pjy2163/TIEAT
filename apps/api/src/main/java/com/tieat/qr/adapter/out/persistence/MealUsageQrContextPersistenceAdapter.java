package com.tieat.qr.adapter.out.persistence;

import com.tieat.qr.domain.MealUsageQrContext;
import com.tieat.qr.domain.MealUsageQrContextId;
import com.tieat.qr.domain.MealUsageQrContextRepository;
import com.tieat.store.domain.StoreId;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MealUsageQrContextPersistenceAdapter implements MealUsageQrContextRepository {

    private final MealUsageQrContextJpaRepository repository;

    public MealUsageQrContextPersistenceAdapter(MealUsageQrContextJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Override
    public Optional<MealUsageQrContext> findByTokenHash(String tokenHash) {
        Objects.requireNonNull(tokenHash, "QR token hash must be supplied");
        return repository.findByTokenHash(tokenHash).map(this::toDomain);
    }

    @Override
    public Optional<MealUsageQrContext> findByTokenHashForUpdate(String tokenHash) {
        Objects.requireNonNull(tokenHash, "QR token hash must be supplied");
        return repository.findByTokenHashForUpdate(tokenHash).map(this::toDomain);
    }

    private MealUsageQrContext toDomain(MealUsageQrContextJpaEntity entity) {
        return new MealUsageQrContext(
            new MealUsageQrContextId(entity.id()),
            new StoreId(entity.storeId()),
            entity.storeDisplayName(),
            entity.tokenHash(),
            entity.expiresAt(),
            entity.revokedAt()
        );
    }
}
