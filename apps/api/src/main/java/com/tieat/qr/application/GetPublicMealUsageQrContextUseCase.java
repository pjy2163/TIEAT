package com.tieat.qr.application;

import com.tieat.partnership.domain.MealContractRepository;
import com.tieat.qr.domain.MealUsageQrContext;
import com.tieat.qr.domain.MealUsageQrContextRepository;
import com.tieat.qr.domain.MealUsageQrToken;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GetPublicMealUsageQrContextUseCase {

    private final MealUsageQrContextRepository mealUsageQrContextRepository;
    private final MealContractRepository mealContractRepository;
    private final Clock clock;

    public GetPublicMealUsageQrContextUseCase(
        MealUsageQrContextRepository mealUsageQrContextRepository,
        MealContractRepository mealContractRepository,
        Clock clock
    ) {
        this.mealUsageQrContextRepository = Objects.requireNonNull(mealUsageQrContextRepository);
        this.mealContractRepository = Objects.requireNonNull(mealContractRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(readOnly = true)
    public PublicMealUsageQrContext get(String rawQrToken) {
        if (!MealUsageQrToken.isValid(rawQrToken)) {
            throw new PublicMealUsageQrNotFoundException();
        }
        Instant now = Instant.now(clock);
        MealUsageQrContext context = mealUsageQrContextRepository.findByTokenHash(MealUsageQrToken.sha256Hash(rawQrToken))
            .filter(candidate -> candidate.isActiveAt(now))
            .orElseThrow(PublicMealUsageQrNotFoundException::new);
        return new PublicMealUsageQrContext(
            context.storeDisplayName(),
            mealContractRepository.findQrSelectableByStoreId(context.storeId()).stream()
                .map(contract -> new PublicMealUsageQrContext.PartnerOption(
                    contract.mealContractId(), contract.partnerDisplayName()
                ))
                .toList(),
            context.expiresAt()
        );
    }
}
