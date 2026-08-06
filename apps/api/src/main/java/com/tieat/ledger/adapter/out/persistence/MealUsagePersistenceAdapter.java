package com.tieat.ledger.adapter.out.persistence;

import com.tieat.ledger.domain.MealUsage;
import com.tieat.ledger.domain.MealUsageId;
import com.tieat.ledger.domain.MealUsageRepository;
import com.tieat.ledger.domain.MealUsageStatus;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.store.domain.StoreId;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MealUsagePersistenceAdapter implements MealUsageRepository {

    private final MealUsageJpaRepository repository;

    public MealUsagePersistenceAdapter(MealUsageJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Override
    public MealUsage save(MealUsage mealUsage) {
        Objects.requireNonNull(mealUsage, "Meal usage must be supplied");
        return toDomain(repository.save(toEntity(mealUsage)));
    }

    @Override
    public Optional<MealUsage> findById(MealUsageId id) {
        Objects.requireNonNull(id, "Meal usage id must be supplied");
        return repository.findById(id.value()).map(this::toDomain);
    }

    private MealUsageJpaEntity toEntity(MealUsage mealUsage) {
        if (mealUsage.status() != MealUsageStatus.PENDING) {
            throw new UnsupportedOperationException(
                "Only pending meal usages can be persisted in the initial schema"
            );
        }
        return new MealUsageJpaEntity(
            mealUsage.id().value(),
            mealUsage.storeId().value(),
            mealUsage.mealContractId().value(),
            mealUsage.entrySource(),
            mealUsage.amount(),
            mealUsage.createdAt(),
            mealUsage.status()
        );
    }

    private MealUsage toDomain(MealUsageJpaEntity entity) {
        if (entity.status() != MealUsageStatus.PENDING) {
            throw new IllegalStateException("Initial schema contains only pending meal usages");
        }
        return MealUsage.pending(
            new MealUsageId(entity.id()),
            new StoreId(entity.storeId()),
            new MealContractId(entity.mealContractId()),
            entity.entrySource(),
            entity.amount(),
            entity.createdAt()
        );
    }
}
