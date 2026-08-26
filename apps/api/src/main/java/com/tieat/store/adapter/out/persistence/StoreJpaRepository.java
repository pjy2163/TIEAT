package com.tieat.store.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface StoreJpaRepository extends JpaRepository<StoreJpaEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select store from StoreJpaEntity store where store.id = :id")
    Optional<StoreJpaEntity> findByIdForUpdate(@Param("id") UUID id);
}
