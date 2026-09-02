package com.tieat.ledger.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import com.tieat.ledger.domain.MealUsageStatus;
import java.util.UUID;
import java.util.Collection;
import java.util.List;
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

    @Query("""
        select count(mealUsage)
        from MealUsageJpaEntity mealUsage
        where mealUsage.storeId = :storeId
          and mealUsage.status = :status
          and (mealUsage.publicQrContextId is null or mealUsage.createdAt > :publicPendingCutoff)
        """)
    long countPendingByStoreIdAndActivePublicCutoff(
        @Param("storeId") UUID storeId,
        @Param("status") MealUsageStatus status,
        @Param("publicPendingCutoff") Instant publicPendingCutoff
    );

    @Query("""
        select mealUsage
        from MealUsageJpaEntity mealUsage
        where mealUsage.storeId = :storeId
          and mealUsage.status = :status
          and (mealUsage.publicQrContextId is null or mealUsage.createdAt > :publicPendingCutoff)
        """)
    Slice<MealUsageJpaEntity> findPendingByStoreIdAndActivePublicCutoff(
        @Param("storeId") UUID storeId,
        @Param("status") MealUsageStatus status,
        @Param("publicPendingCutoff") Instant publicPendingCutoff,
        Pageable pageable
    );

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
        select mealUsage
        from MealUsageJpaEntity mealUsage
        where mealUsage.storeId = :storeId
          and mealUsage.mealContractId = :mealContractId
          and mealUsage.status = :status
          and mealUsage.createdAt >= :startInclusive
          and mealUsage.createdAt < :endExclusive
        """)
    Slice<MealUsageJpaEntity> findByStoreIdAndMealContractIdAndStatusAndCreatedAtBetween(
        @Param("storeId") UUID storeId,
        @Param("mealContractId") UUID mealContractId,
        @Param("status") MealUsageStatus status,
        @Param("startInclusive") Instant startInclusive,
        @Param("endExclusive") Instant endExclusive,
        Pageable pageable
    );

    @Query(value = """
        select allocation.meal_usage_id
        from pos_settlement_allocations allocation
        join meal_usages meal_usage on meal_usage.id = allocation.meal_usage_id
        where meal_usage.store_id = :storeId
          and allocation.meal_usage_id in (:mealUsageIds)
        """, nativeQuery = true)
    List<UUID> findSettlementAllocationUsageIds(
        @Param("storeId") UUID storeId,
        @Param("mealUsageIds") Collection<UUID> mealUsageIds
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
        select coalesce(sum(mealUsage.amount), 0)
        from MealUsageJpaEntity mealUsage
        where mealUsage.storeId = :storeId
          and mealUsage.mealContractId = :mealContractId
          and mealUsage.status = :status
          and mealUsage.createdAt >= :startInclusive
          and mealUsage.createdAt < :endExclusive
        """)
    long sumByStoreIdAndMealContractIdAndStatusAndCreatedAtBetween(
        @Param("storeId") UUID storeId,
        @Param("mealContractId") UUID mealContractId,
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
