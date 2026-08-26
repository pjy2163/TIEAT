package com.tieat.partnership.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface StorePartnerRegistrationJpaRepository extends JpaRepository<StorePartnerRegistrationJpaEntity, StorePartnerRegistrationJpaEntity.Key> {

    Optional<StorePartnerRegistrationJpaEntity> findByStoreIdAndIdempotencyKey(UUID storeId, UUID idempotencyKey);
}
