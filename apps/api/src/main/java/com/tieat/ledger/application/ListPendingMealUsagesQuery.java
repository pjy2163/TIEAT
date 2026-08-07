package com.tieat.ledger.application;

import com.tieat.store.domain.StoreId;
import java.util.Objects;

public record ListPendingMealUsagesQuery(StoreId storeId, String status, int page, int size) {

    public ListPendingMealUsagesQuery {
        Objects.requireNonNull(storeId, "Store id must be supplied");
        if (!"PENDING".equals(status) || page < 0 || size < 1 || size > 100) {
            throw new InvalidPendingMealUsageQueryException();
        }
    }
}
