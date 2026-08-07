package com.tieat.partnership.domain;

import java.util.Optional;

public interface MealContractRepository {

    Optional<MealContract> findById(MealContractId id);

    Optional<MealContract> findByIdForUpdate(MealContractId id);

    MealContract save(MealContract mealContract);
}
