package com.tieat.partnership.adapter.out.persistence;

import com.tieat.partnership.domain.MealContract;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.partnership.domain.MealContractRepository;
import com.tieat.store.domain.StoreId;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MealContractPersistenceAdapter implements MealContractRepository {

    private final MealContractJpaRepository repository;

    public MealContractPersistenceAdapter(MealContractJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Override
    public Optional<MealContract> findById(MealContractId id) {
        Objects.requireNonNull(id, "Meal contract id must be supplied");
        return repository.findById(id.value()).map(this::toDomain);
    }

    @Override
    public Optional<MealContract> findByIdForUpdate(MealContractId id) {
        Objects.requireNonNull(id, "Meal contract id must be supplied");
        return repository.findByIdForUpdate(id.value()).map(this::toDomain);
    }

    @Override
    public MealContract save(MealContract mealContract) {
        Objects.requireNonNull(mealContract, "Meal contract must be supplied");
        return toDomain(repository.saveAndFlush(toEntity(mealContract)));
    }

    private MealContractJpaEntity toEntity(MealContract mealContract) {
        return new MealContractJpaEntity(
            mealContract.id().value(),
            mealContract.storeId().value(),
            mealContract.paymentType(),
            mealContract.prepaidBalance()
        );
    }

    private MealContract toDomain(MealContractJpaEntity entity) {
        return new MealContract(
            new MealContractId(entity.id()),
            new StoreId(entity.storeId()),
            entity.paymentType(),
            entity.prepaidBalance()
        );
    }
}
