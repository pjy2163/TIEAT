package com.tieat.qr.domain;

import java.util.Optional;

public interface MealUsageQrContextRepository {

    Optional<MealUsageQrContext> findByTokenHash(String tokenHash);

    Optional<MealUsageQrContext> findByTokenHashForUpdate(String tokenHash);
}
