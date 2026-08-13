package com.tieat.ledger.adapter.out.persistence;

import com.tieat.ledger.domain.MealUsageId;
import com.tieat.ledger.domain.PublicMealUsageIdempotency;
import com.tieat.ledger.domain.PublicMealUsageIdempotencyRepository;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.qr.domain.MealUsageQrContextId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class PublicMealUsageIdempotencyPersistenceAdapter implements PublicMealUsageIdempotencyRepository {

    private final PublicMealUsageIdempotencyJpaRepository repository;

    public PublicMealUsageIdempotencyPersistenceAdapter(PublicMealUsageIdempotencyJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Override
    public Optional<PublicMealUsageIdempotency> findByQrContextIdAndKey(
        MealUsageQrContextId qrContextId,
        UUID idempotencyKey
    ) {
        Objects.requireNonNull(qrContextId, "Meal usage QR context id must be supplied");
        Objects.requireNonNull(idempotencyKey, "Idempotency key must be supplied");
        return repository.findByQrContextIdAndIdempotencyKey(qrContextId.value(), idempotencyKey).map(this::toDomain);
    }

    @Override
    public PublicMealUsageIdempotency save(PublicMealUsageIdempotency idempotency) {
        Objects.requireNonNull(idempotency, "Public meal usage idempotency must be supplied");
        return toDomain(repository.saveAndFlush(new PublicMealUsageIdempotencyJpaEntity(
            idempotency.qrContextId().value(),
            idempotency.idempotencyKey(),
            idempotency.mealContractId().value(),
            idempotency.amount(),
            idempotency.mealUsageId().value(),
            idempotency.requestKeyHash(),
            idempotency.createdAt()
        )));
    }

    private PublicMealUsageIdempotency toDomain(PublicMealUsageIdempotencyJpaEntity entity) {
        return new PublicMealUsageIdempotency(
            new MealUsageQrContextId(entity.qrContextId()),
            entity.idempotencyKey(),
            new MealContractId(entity.mealContractId()),
            entity.amount(),
            new MealUsageId(entity.mealUsageId()),
            entity.requestKeyHash(),
            entity.createdAt()
        );
    }
}
