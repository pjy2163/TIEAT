package com.tieat.ledger.application;

import com.tieat.ledger.domain.MealUsage;
import com.tieat.ledger.domain.MealUsageRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class ConfirmMealUsageUseCase {

    private final MealUsageRepository mealUsageRepository;
    private final Clock clock;

    public ConfirmMealUsageUseCase(MealUsageRepository mealUsageRepository, Clock clock) {
        this.mealUsageRepository = Objects.requireNonNull(mealUsageRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    public MealUsage confirm(ConfirmMealUsageCommand command) {
        Objects.requireNonNull(command, "Confirm command must be supplied");
        MealUsage mealUsage = mealUsageRepository.findById(command.mealUsageId())
            .orElseThrow(() -> new MealUsageNotFoundException(command.mealUsageId()));

        mealUsage.confirm(command.staffInitials(), Instant.now(clock), command.availablePrepaid());
        return mealUsageRepository.save(mealUsage);
    }
}
