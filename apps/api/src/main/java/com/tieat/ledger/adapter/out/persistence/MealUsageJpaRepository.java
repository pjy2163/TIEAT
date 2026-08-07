package com.tieat.ledger.adapter.out.persistence;

import com.tieat.ledger.domain.MealUsageStatus;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

interface MealUsageJpaRepository extends JpaRepository<MealUsageJpaEntity, UUID> {

    Slice<MealUsageJpaEntity> findByStoreIdAndStatus(UUID storeId, MealUsageStatus status, Pageable pageable);
}
