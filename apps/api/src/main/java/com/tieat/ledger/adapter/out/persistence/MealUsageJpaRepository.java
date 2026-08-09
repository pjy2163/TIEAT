package com.tieat.ledger.adapter.out.persistence;

import com.tieat.ledger.domain.MealUsageStatus;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

interface MealUsageJpaRepository extends JpaRepository<MealUsageJpaEntity, UUID> {

    Slice<MealUsageJpaEntity> findByStoreIdAndStatus(UUID storeId, MealUsageStatus status, Pageable pageable);

    @Query("""
        select count(mealUsage)
        from MealUsageJpaEntity mealUsage
        where mealUsage.publicQrContextId = :qrContextId and mealUsage.createdAt >= :since
        """)
    long countByPublicQrContextIdAndCreatedAtGreaterThanEqual(
        @Param("qrContextId") UUID qrContextId,
        @Param("since") Instant since
    );
}
