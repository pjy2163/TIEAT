package com.tieat.ledger.domain;

import java.util.Optional;

public interface MealUsageRepository {

    MealUsage save(MealUsage mealUsage);

    Optional<MealUsage> findById(MealUsageId id);
}
