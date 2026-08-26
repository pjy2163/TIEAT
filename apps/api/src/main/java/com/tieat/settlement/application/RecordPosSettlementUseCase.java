package com.tieat.settlement.application;

import com.tieat.ledger.application.MealContractNotFoundException;
import com.tieat.ledger.application.MealUsageNotFoundException;
import com.tieat.ledger.domain.MealUsageId;
import com.tieat.ledger.domain.MealUsageStatus;
import com.tieat.settlement.domain.PosSettlement;
import com.tieat.settlement.domain.PosSettlementRepository;
import com.tieat.store.domain.StoreId;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecordPosSettlementUseCase {

    private final PosSettlementRepository repository;
    private final Clock clock;

    public RecordPosSettlementUseCase(PosSettlementRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(readOnly = true)
    public OutstandingReceivableOverview listOutstandingReceivableOverview(StoreId actorStoreId) {
        Objects.requireNonNull(actorStoreId, "Actor store id must be supplied");
        var overview = repository.findOutstandingReceivableOverviewByStoreId(actorStoreId);
        return new OutstandingReceivableOverview(
            overview.items(),
            overview.partners()
        );
    }

    @Transactional(readOnly = true)
    public PosSettlementPage listSettlements(ListPosSettlementsQuery query) {
        Objects.requireNonNull(query, "POS settlement history query must be supplied");
        var slice = repository.findByStoreId(query.storeId(), query.page(), query.size());
        return new PosSettlementPage(slice.items(), query.page(), query.size(), slice.hasNext());
    }

    @Transactional
    public PosSettlement record(RecordPosSettlementCommand command) {
        Objects.requireNonNull(command, "POS settlement command must be supplied");
        repository.lockStoreForSettlement(command.actorStoreId());

        var priorSettlement = repository.findByStoreIdAndIdempotencyKey(
            command.actorStoreId(), command.idempotencyKey()
        );
        if (priorSettlement.isPresent()) {
            PosSettlement settlement = priorSettlement.orElseThrow();
            if (!settlement.matchesRequest(
                command.mealContractId(),
                command.posBusinessDate(),
                command.submittedTotalMinor(),
                command.mealUsageIds()
            )) {
                throw new PosSettlementConflictException(PosSettlementConflictException.Reason.IDEMPOTENCY_KEY_REUSED);
            }
            return settlement;
        }

        if (!repository.existsMealContractByIdAndStoreId(command.mealContractId(), command.actorStoreId())) {
            throw new MealContractNotFoundException(command.mealContractId());
        }

        List<PosSettlementRepository.LockedReceivable> receivables = repository.lockReceivablesByIdAndStoreId(
            command.mealUsageIds(), command.actorStoreId()
        );
        if (receivables.size() != command.mealUsageIds().size()) {
            throw new MealUsageNotFoundException(new MealUsageId(command.mealUsageIds().getFirst()));
        }

        long receivableTotal = 0;
        List<PosSettlement.Allocation> allocations = new java.util.ArrayList<>();
        for (PosSettlementRepository.LockedReceivable receivable : receivables) {
            if (!receivable.mealContractId().equals(command.mealContractId())) {
                throw new PosSettlementConflictException(PosSettlementConflictException.Reason.USAGE_CONTRACT_MISMATCH);
            }
            if (receivable.status() != MealUsageStatus.CONFIRMED
                || receivable.receivableCreatedMinor() == null
                || receivable.receivableCreatedMinor() <= 0) {
                throw new PosSettlementConflictException(PosSettlementConflictException.Reason.USAGE_NOT_OUTSTANDING);
            }
            if (receivable.alreadyAllocated()) {
                throw new PosSettlementConflictException(PosSettlementConflictException.Reason.USAGE_ALREADY_ALLOCATED);
            }
            try {
                receivableTotal = Math.addExact(receivableTotal, receivable.receivableCreatedMinor());
            } catch (ArithmeticException exception) {
                throw new PosSettlementConflictException(PosSettlementConflictException.Reason.TOTAL_MISMATCH);
            }
            allocations.add(new PosSettlement.Allocation(receivable.mealUsageId(), receivable.receivableCreatedMinor()));
        }
        if (receivableTotal != command.submittedTotalMinor()) {
            throw new PosSettlementConflictException(PosSettlementConflictException.Reason.TOTAL_MISMATCH);
        }

        PosSettlement settlement = new PosSettlement(
            java.util.UUID.randomUUID(),
            command.actorStoreId(),
            command.mealContractId(),
            command.posBusinessDate(),
            command.submittedTotalMinor(),
            command.actorLoginId(),
            Instant.now(clock),
            command.idempotencyKey(),
            allocations
        );
        repository.insert(settlement);
        return settlement;
    }

    public record OutstandingReceivableOverview(
        List<PosSettlementRepository.OutstandingReceivable> items,
        List<PosSettlementRepository.PartnerReceivableSummary> partners
    ) {

        public OutstandingReceivableOverview {
            items = List.copyOf(Objects.requireNonNull(items, "Outstanding receivable items must be supplied"));
            partners = List.copyOf(Objects.requireNonNull(partners, "Partner receivable summaries must be supplied"));
        }
    }
}
