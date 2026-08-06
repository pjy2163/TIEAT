package com.tieat.ledger.application;

import com.tieat.ledger.domain.MealUsage;
import com.tieat.ledger.domain.MealUsageId;
import com.tieat.ledger.domain.MealUsageRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class CreateMealUsageUseCase {

    private final MealUsageRepository mealUsageRepository;
    private final Clock clock;

    public CreateMealUsageUseCase(MealUsageRepository mealUsageRepository, Clock clock) {
        this.mealUsageRepository = Objects.requireNonNull(mealUsageRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    public MealUsage create(CreateMealUsageCommand command) {
        Objects.requireNonNull(command, "Create command must be supplied");
        MealUsage mealUsage = MealUsage.pending(
            MealUsageId.newId(),
            command.storeId(),
            command.mealContractId(),
            command.entrySource(),
            command.amount(),
            Instant.now(clock)
        );
        return mealUsageRepository.save(mealUsage);
    }
}
