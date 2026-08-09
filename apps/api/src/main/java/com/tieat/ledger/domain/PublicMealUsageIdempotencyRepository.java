package com.tieat.ledger.domain;

import com.tieat.qr.domain.MealUsageQrContextId;
import java.util.Optional;
import java.util.UUID;

public interface PublicMealUsageIdempotencyRepository {

    Optional<PublicMealUsageIdempotency> findByQrContextIdAndKey(MealUsageQrContextId qrContextId, UUID idempotencyKey);

    PublicMealUsageIdempotency save(PublicMealUsageIdempotency idempotency);
}
