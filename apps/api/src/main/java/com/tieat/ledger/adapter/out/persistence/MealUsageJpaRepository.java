package com.tieat.ledger.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import com.tieat.ledger.domain.MealUsageStatus;
import java.util.UUID;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

interface MealUsageJpaRepository extends JpaRepository<MealUsageJpaEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select mealUsage from MealUsageJpaEntity mealUsage where mealUsage.id = :id")
    Optional<MealUsageJpaEntity> findByIdForUpdate(@Param("id") UUID id);

    Slice<MealUsageJpaEntity> findByStoreIdAndStatus(UUID storeId, MealUsageStatus status, Pageable pageable);

    @Query("""
        select mealUsage
        from MealUsageJpaEntity mealUsage
        where mealUsage.storeId = :storeId
          and mealUsage.status = :status
          and mealUsage.createdAt >= :startInclusive
          and mealUsage.createdAt < :endExclusive
        """)
    Slice<MealUsageJpaEntity> findByStoreIdAndStatusAndCreatedAtBetween(
        @Param("storeId") UUID storeId,
        @Param("status") MealUsageStatus status,
        @Param("startInclusive") Instant startInclusive,
        @Param("endExclusive") Instant endExclusive,
        Pageable pageable
    );

    @Query("""
        select coalesce(sum(mealUsage.amount), 0)
        from MealUsageJpaEntity mealUsage
        where mealUsage.storeId = :storeId
          and mealUsage.status = :status
          and mealUsage.createdAt >= :startInclusive
          and mealUsage.createdAt < :endExclusive
        """)
    long sumByStoreIdAndStatusAndCreatedAtBetween(
        @Param("storeId") UUID storeId,
        @Param("status") MealUsageStatus status,
        @Param("startInclusive") Instant startInclusive,
        @Param("endExclusive") Instant endExclusive
    );

    @Query("""
        select count(mealUsage)
        from MealUsageJpaEntity mealUsage
        where mealUsage.publicQrContextId = :qrContextId and mealUsage.createdAt >= :since
        """)
    long countByPublicQrContextIdAndCreatedAtGreaterThanEqual(
        @Param("qrContextId") UUID qrContextId,
        @Param("since") Instant since
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        update meal_usages
        set customer_name = null
        where customer_name is not null
          and created_at < :cutoffExclusive
        """, nativeQuery = true)
    int anonymizeCustomerNamesCreatedBefore(@Param("cutoffExclusive") Instant cutoffExclusive);
}
