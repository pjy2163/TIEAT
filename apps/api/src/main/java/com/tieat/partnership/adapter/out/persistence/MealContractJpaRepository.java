package com.tieat.partnership.adapter.out.persistence;

import jakarta.persistence.LockModeType;
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
        select mealContract
        from MealContractJpaEntity mealContract
        join fetch mealContract.partnerOrganization
        where mealContract.storeId = :storeId and mealContract.qrSelectable = true
        """)
    List<MealContractJpaEntity> findQrSelectableByStoreId(@Param("storeId") UUID storeId);
}
