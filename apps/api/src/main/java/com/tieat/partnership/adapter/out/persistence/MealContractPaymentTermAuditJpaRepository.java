package com.tieat.partnership.adapter.out.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface MealContractPaymentTermAuditJpaRepository extends JpaRepository<MealContractPaymentTermAuditJpaEntity, UUID> {
}
