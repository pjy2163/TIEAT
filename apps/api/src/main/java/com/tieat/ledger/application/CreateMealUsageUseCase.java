package com.tieat.ledger.application;

import com.tieat.ledger.domain.MealUsage;
import com.tieat.ledger.domain.MealUsageId;
import com.tieat.ledger.domain.MealUsageRepository;
import com.tieat.partnership.domain.MealContractRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreateMealUsageUseCase {

    private final MealUsageRepository mealUsageRepository;
    private final MealContractRepository mealContractRepository;
    private final Clock clock;

    public CreateMealUsageUseCase(
        MealUsageRepository mealUsageRepository,
        MealContractRepository mealContractRepository,
        Clock clock
    ) {
        this.mealUsageRepository = Objects.requireNonNull(mealUsageRepository);
        this.mealContractRepository = Objects.requireNonNull(mealContractRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public MealUsage create(CreateMealUsageCommand command) {
        Objects.requireNonNull(command, "Create command must be supplied");
        var mealContract = mealContractRepository.findByIdForUpdate(command.mealContractId())
            .orElseThrow(() -> new MealContractNotFoundException(command.mealContractId()));
        if (!mealContract.storeId().equals(command.storeId()) || mealContract.isArchived()) {
            throw new MealContractNotFoundException(command.mealContractId());
        }
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
