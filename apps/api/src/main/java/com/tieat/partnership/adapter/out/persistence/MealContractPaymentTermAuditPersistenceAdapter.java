package com.tieat.partnership.adapter.out.persistence;

import com.tieat.partnership.domain.MealContractPaymentTermAudit;
import com.tieat.partnership.domain.MealContractPaymentTermAuditRepository;
import java.util.Objects;
import org.springframework.stereotype.Repository;

@Repository
public class MealContractPaymentTermAuditPersistenceAdapter implements MealContractPaymentTermAuditRepository {

    private final MealContractPaymentTermAuditJpaRepository repository;

    public MealContractPaymentTermAuditPersistenceAdapter(MealContractPaymentTermAuditJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Override
    public MealContractPaymentTermAudit save(MealContractPaymentTermAudit audit) {
        Objects.requireNonNull(audit, "Payment term audit must be supplied");
        repository.saveAndFlush(new MealContractPaymentTermAuditJpaEntity(
            audit.id(),
            audit.storeId().value(),
            audit.mealContractId().value(),
            audit.actorLoginId(),
            audit.previousPaymentType(),
            audit.newPaymentType(),
            audit.prepaidBalanceBefore(),
            audit.prepaidBalanceAfter(),
            audit.occurredAt()
        ));
        return audit;
    }
}
