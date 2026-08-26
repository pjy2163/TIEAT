package com.tieat.settlement.application;

import com.tieat.store.domain.StoreId;
import java.util.Objects;

public record ListPosSettlementsQuery(
    StoreId storeId,
    int page,
    int size,
    String partnerDisplayName,
    String search
) {

    public ListPosSettlementsQuery(StoreId storeId, int page, int size) {
        this(storeId, page, size, null, null);
    }

    public ListPosSettlementsQuery {
        Objects.requireNonNull(storeId, "Store id must be supplied");
        if (page < 0 || size < 1 || size > 100) {
            throw new InvalidPosSettlementHistoryQueryException();
        }
        partnerDisplayName = normalizeFilter(partnerDisplayName);
        search = normalizeFilter(search);
    }

    private static String normalizeFilter(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > 100) {
            if (normalized.length() > 100) throw new InvalidPosSettlementHistoryQueryException();
            return null;
        }
        return normalized;
    }
}
