package com.tieat.settlement.application;

import com.tieat.ledger.application.MealContractNotFoundException;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.settlement.domain.CumulativeSettlementSnapshot;
import com.tieat.settlement.domain.PosSettlementRepository;
import com.tieat.store.domain.StoreId;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CumulativeSettlementExportService {

    private final PosSettlementRepository repository;
    private final CumulativeSettlementWorkbookGenerator workbookGenerator;
    private final Clock clock;

    public CumulativeSettlementExportService(
        PosSettlementRepository repository,
        CumulativeSettlementWorkbookGenerator workbookGenerator,
        Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.workbookGenerator = Objects.requireNonNull(workbookGenerator);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(readOnly = true)
    public byte[] export(StoreId actorStoreId, MealContractId mealContractId) {
        Objects.requireNonNull(actorStoreId, "Actor store id must be supplied");
        Objects.requireNonNull(mealContractId, "Meal contract id must be supplied");
        Instant generatedAt = Instant.now(clock);
        CumulativeSettlementSnapshot snapshot = repository
            .findCumulativeSettlementSnapshotByMealContractIdAndStoreId(mealContractId, actorStoreId, generatedAt)
            .orElseThrow(() -> new MealContractNotFoundException(mealContractId));
        return workbookGenerator.generate(snapshot);
    }
}
