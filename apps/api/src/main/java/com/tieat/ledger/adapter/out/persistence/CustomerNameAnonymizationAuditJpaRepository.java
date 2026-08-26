package com.tieat.ledger.adapter.out.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface CustomerNameAnonymizationAuditJpaRepository extends JpaRepository<CustomerNameAnonymizationAuditJpaEntity, UUID> {
}
