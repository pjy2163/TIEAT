package com.tieat.qr.application;

import com.tieat.qr.domain.MealUsageQrContext;
import com.tieat.qr.domain.MealUsageQrOperationsRepository;
import com.tieat.store.domain.StoreId;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GetStoreMealUsageQrViewUseCase {

    private final MealUsageQrOperationsRepository repository;
    private final MealUsageQrTokenProtector tokenProtector;
    private final Clock clock;

    public GetStoreMealUsageQrViewUseCase(
        MealUsageQrOperationsRepository repository,
        MealUsageQrTokenProtector tokenProtector,
        Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.tokenProtector = Objects.requireNonNull(tokenProtector);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(readOnly = true)
    public StoreMealUsageQrView get(StoreId storeId) {
        Objects.requireNonNull(storeId, "Store id must be supplied");
        MealUsageQrContext context = repository.findCurrentByStoreId(storeId)
            .orElse(null);
        if (context == null) {
            return StoreMealUsageQrView.notAvailable();
        }

        Instant now = Instant.now(clock);
        if (context.protectedToken().isEmpty()) {
            return StoreMealUsageQrView.reissueRequired(context);
        }
        try {
            String rawToken = tokenProtector.reveal(context);
            if (!context.expiresAt().isAfter(now)) {
                return StoreMealUsageQrView.expired(context);
            }
            return StoreMealUsageQrView.available(context, "/qr/" + rawToken);
        } catch (QrTokenProtectionException exception) {
            return StoreMealUsageQrView.reissueRequired(context);
        }
    }

    public record StoreMealUsageQrView(
        Status status,
        String publicPath,
        Instant issuedAt,
        Instant expiresAt
    ) {

        public StoreMealUsageQrView {
            Objects.requireNonNull(status, "QR view status must be supplied");
        }

        static StoreMealUsageQrView notAvailable() {
            return new StoreMealUsageQrView(Status.NOT_AVAILABLE, null, null, null);
        }

        static StoreMealUsageQrView expired(MealUsageQrContext context) {
            return new StoreMealUsageQrView(Status.EXPIRED, null, context.issuedAt(), context.expiresAt());
        }

        static StoreMealUsageQrView reissueRequired(MealUsageQrContext context) {
            return new StoreMealUsageQrView(Status.REISSUE_REQUIRED, null, context.issuedAt(), context.expiresAt());
        }

        static StoreMealUsageQrView available(MealUsageQrContext context, String publicPath) {
            return new StoreMealUsageQrView(
                Status.AVAILABLE,
                publicPath,
                context.issuedAt(),
                context.expiresAt()
            );
        }

        public enum Status {
            AVAILABLE,
            NOT_AVAILABLE,
            EXPIRED,
            REISSUE_REQUIRED
        }
    }
}
