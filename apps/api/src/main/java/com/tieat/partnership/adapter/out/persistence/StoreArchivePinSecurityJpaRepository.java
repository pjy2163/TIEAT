package com.tieat.partnership.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface StoreArchivePinSecurityJpaRepository extends JpaRepository<StoreArchivePinSecurityJpaEntity, UUID> {

    Optional<StoreArchivePinSecurityJpaEntity> findByStoreId(UUID storeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select security from StoreArchivePinSecurityJpaEntity security where security.storeId = :storeId")
    Optional<StoreArchivePinSecurityJpaEntity> findByStoreIdForUpdate(@Param("storeId") UUID storeId);
}
