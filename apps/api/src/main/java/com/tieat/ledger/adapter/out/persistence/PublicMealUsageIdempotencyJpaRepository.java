package com.tieat.ledger.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PublicMealUsageIdempotencyJpaRepository extends JpaRepository<PublicMealUsageIdempotencyJpaEntity, PublicMealUsageIdempotencyJpaEntity.Key> {

    Optional<PublicMealUsageIdempotencyJpaEntity> findByQrContextIdAndIdempotencyKey(UUID qrContextId, UUID idempotencyKey);
}
