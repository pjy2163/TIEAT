package com.tieat.partnership.domain;

import com.tieat.store.domain.StoreId;
import java.util.Optional;
import java.util.UUID;

public interface StorePartnerRegistrationRepository {

    void lockStore(StoreId storeId);

    Optional<StorePartnerRegistration> findByStoreIdAndIdempotencyKey(StoreId storeId, UUID idempotencyKey);

    StorePartnerRegistration save(StorePartnerRegistration registration);
}
