package com.tieat.ledger.application;

import com.tieat.ledger.domain.MealUsage;
import com.tieat.ledger.domain.MealUsageId;
import com.tieat.ledger.domain.MealUsageRepository;
import com.tieat.ledger.domain.PublicMealUsageIdempotency;
import com.tieat.ledger.domain.PublicMealUsageIdempotencyRepository;
import com.tieat.partnership.domain.QrSelectableMealContract;
import com.tieat.partnership.domain.MealContract;
import com.tieat.partnership.domain.MealContractRepository;
import com.tieat.qr.application.PublicMealUsageQrNotFoundException;
import com.tieat.qr.domain.MealUsageQrContext;
import com.tieat.qr.domain.MealUsageQrContextRepository;
import com.tieat.qr.domain.MealUsageQrToken;
import com.tieat.security.application.RateLimiter;
import com.tieat.security.web.RateLimitKeys;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class CreatePublicMealUsageUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreatePublicMealUsageUseCase.class);

    private static final long MAX_SUCCESSES_PER_MINUTE = 10;
    private static final int MAX_CLIENT_CREATES_PER_MINUTE = 5;

    private final MealUsageQrContextRepository mealUsageQrContextRepository;
    private final MealContractRepository mealContractRepository;
    private final MealUsageRepository mealUsageRepository;
    private final PublicMealUsageIdempotencyRepository idempotencyRepository;
    private final Clock clock;
    private final RateLimiter rateLimiter;

    public CreatePublicMealUsageUseCase(
        MealUsageQrContextRepository mealUsageQrContextRepository,
        MealContractRepository mealContractRepository,
        MealUsageRepository mealUsageRepository,
        PublicMealUsageIdempotencyRepository idempotencyRepository,
        Clock clock,
        RateLimiter rateLimiter
    ) {
        this.mealUsageQrContextRepository = Objects.requireNonNull(mealUsageQrContextRepository);
        this.mealContractRepository = Objects.requireNonNull(mealContractRepository);
        this.mealUsageRepository = Objects.requireNonNull(mealUsageRepository);
        this.idempotencyRepository = Objects.requireNonNull(idempotencyRepository);
        this.clock = Objects.requireNonNull(clock);
        this.rateLimiter = Objects.requireNonNull(rateLimiter);
    }

    @Transactional
    public MealUsage create(CreatePublicMealUsageCommand command) {
        Objects.requireNonNull(command, "Public create command must be supplied");
        if (!MealUsageQrToken.isValid(command.rawQrToken())) {
            throw new PublicMealUsageQrNotFoundException();
        }
        final Instant initialNow = Instant.now(clock);
        String requestKeyHash = command.requestKeyHash();
        String tokenHash = MealUsageQrToken.sha256Hash(command.rawQrToken());
        MealUsageQrContext contextHint = mealUsageQrContextRepository.findByTokenHash(tokenHash)
            .filter(candidate -> candidate.isActiveAt(initialNow))
            .orElseThrow(PublicMealUsageQrNotFoundException::new);
        mealUsageRepository.lockStoreForPendingCreation(contextHint.storeId());
        final Instant now = Instant.now(clock);
        MealUsageQrContext context = mealUsageQrContextRepository.findByTokenHashForUpdate(
            tokenHash
        )
            .filter(candidate -> candidate.isActiveAt(now))
            .orElseThrow(PublicMealUsageQrNotFoundException::new);

        var priorRequest = idempotencyRepository.findByQrContextIdAndKey(context.id(), command.idempotencyKey());
        if (priorRequest.isPresent()) {
            PublicMealUsageIdempotency prior = priorRequest.orElseThrow();
            if (!prior.matchesCreatePayload(command.mealContractId(), command.amount(), requestKeyHash)) {
                throw new PublicMealUsageIdempotencyConflictException();
            }
            if (!prior.isRequestKeyActiveAt(now)) {
                throw new PublicMealUsageIdempotencyConflictException();
            }
            MealUsage existing = mealUsageRepository.findById(prior.mealUsageId())
                .orElseThrow(() -> new IllegalStateException("Idempotency record refers to a missing meal usage"));
            if (existing.customerNameSnapshot().filter(command.customerName()::equals).isEmpty()) {
                throw new PublicMealUsageIdempotencyConflictException();
            }
            return existing;
        }
        if (!context.acceptingNewRequests()) {
            throw new PublicQrCreationPausedException();
        }

        RateLimiter.Decision clientDecision = rateLimiter.consume(
            RateLimitKeys.publicQrCreateClient(context.id(), command.publicClientKey()),
            MAX_CLIENT_CREATES_PER_MINUTE, java.time.Duration.ofMinutes(1)
        );
        if (!clientDecision.allowed()) {
            log.warn("security_event=public_qr_bot_challenge_recommended");
            throw new PublicMealUsageRateLimitExceededException(clientDecision.retryAfter());
        }

        MealContract lockedContract = mealContractRepository.findByIdForUpdate(command.mealContractId())
            .filter(contract -> contract.storeId().equals(context.storeId())
                && contract.isQrSelectable()
                && !contract.isArchived())
            .orElseThrow(PublicQrMealContractNotFoundException::new);
        QrSelectableMealContract selectedContract = mealContractRepository.findQrSelectableByStoreId(lockedContract.storeId()).stream()
            .filter(contract -> contract.mealContractId().equals(command.mealContractId()))
            .findFirst()
            .orElseThrow(PublicQrMealContractNotFoundException::new);
        if (mealUsageRepository.countPendingByStoreId(context.storeId(), now) >= MealUsageRepository.MAX_PENDING_PER_STORE) {
            throw new MealUsagePendingLimitReachedException();
        }
        if (mealUsageRepository.countPublicQrCreatedSince(context.id(), now.minusSeconds(60)) >= MAX_SUCCESSES_PER_MINUTE) {
            throw new PublicMealUsageRateLimitExceededException();
        }
        final Instant createdAt = now.plusNanos(500).truncatedTo(ChronoUnit.MICROS);
        MealUsage created = mealUsageRepository.save(MealUsage.pendingFromPublicQr(
            MealUsageId.newId(),
            context.storeId(),
            selectedContract.mealContractId(),
            context.id(),
            selectedContract.partnerDisplayName(),
            command.customerName(),
            command.amount(),
            createdAt
        ));
        idempotencyRepository.save(new PublicMealUsageIdempotency(
            context.id(), command.idempotencyKey(), command.mealContractId(), command.amount(), created.id(), requestKeyHash, createdAt
        ));
        return created;
    }
}
