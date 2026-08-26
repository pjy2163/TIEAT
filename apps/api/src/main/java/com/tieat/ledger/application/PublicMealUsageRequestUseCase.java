package com.tieat.ledger.application;

import com.tieat.ledger.domain.MealUsage;
import com.tieat.ledger.domain.MealUsageId;
import com.tieat.ledger.domain.MealUsageRepository;
import com.tieat.ledger.domain.MealUsageStatus;
import com.tieat.ledger.domain.PublicMealUsageIdempotency;
import com.tieat.ledger.domain.PublicMealUsageIdempotencyRepository;
import com.tieat.qr.application.PublicMealUsageQrNotFoundException;
import com.tieat.qr.domain.MealUsageQrContext;
import com.tieat.qr.domain.MealUsageQrContextRepository;
import com.tieat.qr.domain.MealUsageQrToken;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicMealUsageRequestUseCase {

    private final MealUsageQrContextRepository mealUsageQrContextRepository;
    private final PublicMealUsageIdempotencyRepository idempotencyRepository;
    private final MealUsageRepository mealUsageRepository;
    private final Clock clock;

    public PublicMealUsageRequestUseCase(
        MealUsageQrContextRepository mealUsageQrContextRepository,
        PublicMealUsageIdempotencyRepository idempotencyRepository,
        MealUsageRepository mealUsageRepository,
        Clock clock
    ) {
        this.mealUsageQrContextRepository = Objects.requireNonNull(mealUsageQrContextRepository);
        this.idempotencyRepository = Objects.requireNonNull(idempotencyRepository);
        this.mealUsageRepository = Objects.requireNonNull(mealUsageRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public MealUsage get(RequestCommand command) {
        Objects.requireNonNull(command, "Public request command must be supplied");
        return loadAuthorizedUsage(command, Instant.now(clock), false);
    }

    @Transactional
    public MealUsage cancel(RequestCommand command) {
        Objects.requireNonNull(command, "Public request command must be supplied");
        Instant now = Instant.now(clock);
        MealUsage mealUsage = loadAuthorizedUsage(command, now, true);
        if (mealUsage.status() == MealUsageStatus.CANCELLED) {
            return mealUsage;
        }
        if (mealUsage.status() != MealUsageStatus.PENDING) {
            throw new PublicMealUsageQrNotFoundException();
        }
        mealUsage.cancelFromPublicQr(now);
        return mealUsageRepository.save(mealUsage);
    }

    private MealUsage loadAuthorizedUsage(RequestCommand command, Instant now, boolean lockUsage) {
        MealUsageQrContext context = mealUsageQrContextRepository.findByTokenHashForUpdate(
            MealUsageQrToken.sha256Hash(command.rawQrToken())
        )
            .filter(candidate -> candidate.isActiveAt(now))
            .orElseThrow(PublicMealUsageQrNotFoundException::new);
        PublicMealUsageIdempotency idempotency = idempotencyRepository.findByQrContextIdAndKey(
            context.id(), command.idempotencyKey()
        ).orElseThrow(PublicMealUsageQrNotFoundException::new);
        if (!idempotency.mealUsageId().equals(command.mealUsageId())
            || !idempotency.matchesRequestKey(command.rawRequestKey())
            || !idempotency.isRequestKeyActiveAt(now)) {
            throw new PublicMealUsageQrNotFoundException();
        }
        return (lockUsage ? mealUsageRepository.findByIdForUpdate(command.mealUsageId()) : mealUsageRepository.findById(command.mealUsageId()))
            .filter(usage -> usage.publicQrContextId().filter(context.id()::equals).isPresent())
            .orElseThrow(PublicMealUsageQrNotFoundException::new);
    }

    public record RequestCommand(
        String rawQrToken,
        UUID idempotencyKey,
        MealUsageId mealUsageId,
        String rawRequestKey
    ) {

        public RequestCommand {
            Objects.requireNonNull(rawQrToken, "Raw QR token must be supplied");
            Objects.requireNonNull(idempotencyKey, "Idempotency key must be supplied");
            Objects.requireNonNull(mealUsageId, "Meal usage id must be supplied");
            Objects.requireNonNull(rawRequestKey, "Public request key must be supplied");
        }
    }
}
