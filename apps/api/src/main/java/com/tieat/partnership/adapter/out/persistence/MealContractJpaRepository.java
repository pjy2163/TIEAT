package com.tieat.partnership.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import com.tieat.partnership.domain.MealContractPaymentType;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MealContractJpaRepository extends JpaRepository<MealContractJpaEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select mealContract from MealContractJpaEntity mealContract where mealContract.id = :id")
    Optional<MealContractJpaEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
        select mealContract.id as mealContractId, partnerOrganization.displayName as partnerDisplayName
        from MealContractJpaEntity mealContract, PartnerOrganizationJpaEntity partnerOrganization
        where mealContract.partnerOrganizationId = partnerOrganization.id
          and mealContract.storeId = :storeId
          and mealContract.archivedAt is null
          and mealContract.qrSelectable = true
        """)
    List<QrSelectableMealContractProjection> findQrSelectableByStoreId(@Param("storeId") UUID storeId);

    @Query("""
        select mealContract.id as mealContractId,
               mealContract.partnerOrganizationId as partnerOrganizationId,
               partnerOrganization.displayName as partnerDisplayName,
               partnerOrganization.partnerKind as partnerKind,
               mealContract.paymentType as paymentType,
               mealContract.qrSelectable as qrSelectable,
               partnerOrganization.representativePhone as representativePhone,
               partnerOrganization.representativeEmail as representativeEmail
        from MealContractJpaEntity mealContract, PartnerOrganizationJpaEntity partnerOrganization
        where mealContract.partnerOrganizationId = partnerOrganization.id
          and mealContract.storeId = :storeId
          and mealContract.archivedAt is null
        order by partnerOrganization.displayName asc, mealContract.id asc
        """)
    List<StorePartnerDirectoryProjection> findPartnerDirectoryByStoreId(@Param("storeId") UUID storeId);

    @Query("""
        select mealContract.id as mealContractId,
               mealContract.partnerOrganizationId as partnerOrganizationId,
               partnerOrganization.displayName as partnerDisplayName,
               partnerOrganization.partnerKind as partnerKind,
               mealContract.paymentType as paymentType,
               mealContract.qrSelectable as qrSelectable,
               partnerOrganization.representativePhone as representativePhone,
               partnerOrganization.representativeEmail as representativeEmail
        from MealContractJpaEntity mealContract, PartnerOrganizationJpaEntity partnerOrganization
        where mealContract.partnerOrganizationId = partnerOrganization.id
          and mealContract.storeId = :storeId
          and mealContract.archivedAt is null
          and mealContract.id = :mealContractId
        """)
    Optional<StorePartnerDirectoryProjection> findPartnerDirectoryEntryByIdAndStoreId(
        @Param("mealContractId") UUID mealContractId,
        @Param("storeId") UUID storeId
    );

    @Query(value = """
        select exists(
            select 1
            from meal_usages mealUsage
            where mealUsage.store_id = :storeId
              and mealUsage.meal_contract_id = :mealContractId
              and mealUsage.status = 'PENDING'
        )
        """, nativeQuery = true)
    boolean existsPendingUsageByStoreIdAndMealContractId(
        @Param("storeId") UUID storeId,
        @Param("mealContractId") UUID mealContractId
    );

    @Query(value = """
        select exists(
            select 1
            from meal_usages mealUsage
            where mealUsage.store_id = :storeId
              and mealUsage.meal_contract_id = :mealContractId
              and mealUsage.status = 'CONFIRMED'
              and mealUsage.receivable_created > 0
              and not exists (
                  select 1
                  from pos_settlement_allocations allocation
                  where allocation.meal_usage_id = mealUsage.id
              )
        )
        """, nativeQuery = true)
    boolean existsOutstandingReceivableByStoreIdAndMealContractId(
        @Param("storeId") UUID storeId,
        @Param("mealContractId") UUID mealContractId
    );
}

interface QrSelectableMealContractProjection {

    UUID getMealContractId();

    String getPartnerDisplayName();
}

interface StorePartnerDirectoryProjection {

    UUID getMealContractId();

    UUID getPartnerOrganizationId();

    String getPartnerDisplayName();

    com.tieat.partnership.domain.PartnerKind getPartnerKind();

    MealContractPaymentType getPaymentType();

    boolean getQrSelectable();

    String getRepresentativePhone();

    String getRepresentativeEmail();
}
