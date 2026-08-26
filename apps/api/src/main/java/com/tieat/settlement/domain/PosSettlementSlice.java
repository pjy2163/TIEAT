package com.tieat.settlement.domain;

import java.util.List;
import java.util.Objects;

/**
 * A header-paginated, complete-settlement view for a single authenticated store.
 *
 * <p>Every item contains all of its immutable allocation rows. The persistence adapter must page
 * settlement headers before it loads allocations so an allocation list cannot be split across pages.</p>
 */
public record PosSettlementSlice(List<PosSettlement> items, boolean hasNext) {

    public PosSettlementSlice {
        items = List.copyOf(Objects.requireNonNull(items, "POS settlement items must be supplied"));
    }
}
