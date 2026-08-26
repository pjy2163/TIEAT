package com.tieat.settlement.application;

import com.tieat.store.domain.StoreId;
import java.util.Objects;

public record ListPosSettlementsQuery(StoreId storeId, int page, int size) {

    public ListPosSettlementsQuery {
        Objects.requireNonNull(storeId, "Store id must be supplied");
        if (page < 0 || size < 1 || size > 100) {
            throw new InvalidPosSettlementHistoryQueryException();
        }
    }
}
