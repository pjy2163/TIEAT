package com.tieat.ledger.application;

import com.tieat.ledger.domain.MealUsage;
import com.tieat.ledger.domain.MealUsageRepository;
import com.tieat.ledger.domain.PrepaidAllocation;
import com.tieat.partnership.domain.MealContract;
import com.tieat.partnership.domain.MealContractAllocation;
import com.tieat.partnership.domain.MealContractRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConfirmMealUsageUseCase {

    private final MealUsageRepository mealUsageRepository;
    private final MealContractRepository mealContractRepository;
    private final Clock clock;

    public ConfirmMealUsageUseCase(
        MealUsageRepository mealUsageRepository,
        MealContractRepository mealContractRepository,
        Clock clock
    ) {
        this.mealUsageRepository = Objects.requireNonNull(mealUsageRepository);
        this.mealContractRepository = Objects.requireNonNull(mealContractRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public MealUsage confirm(ConfirmMealUsageCommand command) {
        Objects.requireNonNull(command, "Confirm command must be supplied");
        MealUsage mealUsage = mealUsageRepository.findById(command.mealUsageId())
            .orElseThrow(() -> new MealUsageNotFoundException(command.mealUsageId()));
        if (!mealUsage.storeId().equals(command.actorStoreId())) {
            throw new MealUsageNotFoundException(command.mealUsageId());
        }
        if (mealUsage.status() == com.tieat.ledger.domain.MealUsageStatus.CONFIRMED) {
            throw new MealUsageAlreadyConfirmedException(mealUsage.id());
        }
        if (mealUsage.status() != com.tieat.ledger.domain.MealUsageStatus.PENDING) {
            throw new MealUsageNotPendingException(mealUsage.id());
        }
        MealContract mealContract = mealContractRepository.findByIdForUpdate(mealUsage.mealContractId())
            .orElseThrow(() -> new MealContractNotFoundException(mealUsage.mealContractId()));
        if (!mealUsage.storeId().equals(mealContract.storeId())) {
            throw new MealUsageContractScopeMismatchException(mealUsage, mealContract);
        }

        MealContractAllocation allocation = mealContract.allocate(mealUsage.amount());
        mealUsage.confirm(
            command.staffInitials(),
            Instant.now(clock),
            new PrepaidAllocation(
                allocation.usageAmount(),
                allocation.prepaidApplied(),
                allocation.receivableCreated(),
                allocation.remainingPrepaid()
            )
        );
        mealContractRepository.save(mealContract);
        return mealUsageRepository.save(mealUsage);
    }
}
