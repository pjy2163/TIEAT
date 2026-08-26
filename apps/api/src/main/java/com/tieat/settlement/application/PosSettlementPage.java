package com.tieat.settlement.application;

import com.tieat.settlement.domain.PosSettlement;
import java.util.List;
import java.util.Objects;

public record PosSettlementPage(List<PosSettlement> items, int page, int size, boolean hasNext) {

    public PosSettlementPage {
        items = List.copyOf(Objects.requireNonNull(items, "POS settlement page items must be supplied"));
    }
}
