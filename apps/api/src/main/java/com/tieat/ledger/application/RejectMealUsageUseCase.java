package com.tieat.ledger.application;

import com.tieat.ledger.domain.MealUsage;
import com.tieat.ledger.domain.MealUsageRepository;
import com.tieat.ledger.domain.MealUsageStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RejectMealUsageUseCase {

    private final MealUsageRepository mealUsageRepository;
    private final Clock clock;

    public RejectMealUsageUseCase(MealUsageRepository mealUsageRepository, Clock clock) {
        this.mealUsageRepository = Objects.requireNonNull(mealUsageRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public MealUsage reject(RejectMealUsageCommand command) {
        Objects.requireNonNull(command, "Reject command must be supplied");
        MealUsage mealUsage = mealUsageRepository.findByIdForUpdate(command.mealUsageId())
            .orElseThrow(() -> new MealUsageNotFoundException(command.mealUsageId()));
        if (!mealUsage.storeId().equals(command.actorStoreId())) {
            throw new MealUsageNotFoundException(command.mealUsageId());
        }
        if (mealUsage.status() != MealUsageStatus.PENDING) {
            throw new MealUsageNotPendingException(mealUsage.id());
        }
        Instant now = Instant.now(clock);
        if (mealUsage.publicQrContextId().isPresent() && !mealUsage.isPublicQrPendingActiveAt(now)) {
            throw new MealUsageNotPendingException(mealUsage.id());
        }
        mealUsage.reject(command.actorLoginId(), now);
        return mealUsageRepository.save(mealUsage);
    }
}
