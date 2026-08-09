package com.tieat.qr.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MealUsageQrContextJpaRepository extends JpaRepository<MealUsageQrContextJpaEntity, UUID> {

    Optional<MealUsageQrContextJpaEntity> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select context from MealUsageQrContextJpaEntity context where context.tokenHash = :tokenHash")
    Optional<MealUsageQrContextJpaEntity> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);
}
