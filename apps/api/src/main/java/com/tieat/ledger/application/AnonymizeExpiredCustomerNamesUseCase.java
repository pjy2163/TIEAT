package com.tieat.ledger.application;

import com.tieat.ledger.domain.MealUsageRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnonymizeExpiredCustomerNamesUseCase {

    private static final ZoneId BUSINESS_TIME_ZONE = ZoneId.of("Asia/Seoul");

    private final MealUsageRepository mealUsageRepository;
    private final Clock clock;

    public AnonymizeExpiredCustomerNamesUseCase(MealUsageRepository mealUsageRepository, Clock clock) {
        this.mealUsageRepository = Objects.requireNonNull(mealUsageRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public Result anonymize() {
        Instant executedAt = Instant.now(clock);
        Instant cutoffExclusive = executedAt.atZone(BUSINESS_TIME_ZONE).minusMonths(3).toInstant();
        int affectedCount = mealUsageRepository.anonymizeCustomerNamesCreatedBefore(cutoffExclusive, executedAt);
        return new Result(executedAt, cutoffExclusive, affectedCount);
    }

    public record Result(Instant executedAt, Instant cutoffExclusive, int affectedCount) {

        public Result {
            Objects.requireNonNull(executedAt, "Customer name anonymization time must be supplied");
            Objects.requireNonNull(cutoffExclusive, "Customer name cutoff must be supplied");
            if (affectedCount < 0) {
                throw new IllegalArgumentException("Customer name anonymization count must not be negative");
            }
        }
    }
}
