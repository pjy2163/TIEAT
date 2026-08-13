package com.tieat.settlement.application;

import com.tieat.partnership.domain.MealContractId;
import com.tieat.store.domain.StoreId;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record RecordPosSettlementCommand(
    StoreId actorStoreId,
    String actorLoginId,
    UUID idempotencyKey,
    MealContractId mealContractId,
    LocalDate posBusinessDate,
    long submittedTotalMinor,
    List<UUID> mealUsageIds
) {

    public RecordPosSettlementCommand {
        Objects.requireNonNull(actorStoreId, "Actor store id must be supplied");
        if (actorLoginId == null || actorLoginId.isBlank()) {
            throw new IllegalArgumentException("Actor login id must be supplied");
        }
        Objects.requireNonNull(idempotencyKey, "Idempotency key must be supplied");
        Objects.requireNonNull(mealContractId, "Meal contract id must be supplied");
        Objects.requireNonNull(posBusinessDate, "POS business date must be supplied");
        if (submittedTotalMinor <= 0) {
            throw new IllegalArgumentException("Submitted POS total must be positive");
        }
        Objects.requireNonNull(mealUsageIds, "Meal usage ids must be supplied");
        mealUsageIds = mealUsageIds.stream()
            .peek(id -> Objects.requireNonNull(id, "Meal usage id must be supplied"))
            .sorted(Comparator.naturalOrder())
            .toList();
        if (mealUsageIds.isEmpty()) {
            throw new IllegalArgumentException("At least one meal usage id must be supplied");
        }
        Set<UUID> uniqueUsageIds = new HashSet<>(mealUsageIds);
        if (uniqueUsageIds.size() != mealUsageIds.size()) {
            throw new IllegalArgumentException("Meal usage ids must be unique");
        }
    }
}
