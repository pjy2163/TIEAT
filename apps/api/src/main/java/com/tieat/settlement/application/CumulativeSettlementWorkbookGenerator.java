package com.tieat.settlement.application;

import com.tieat.settlement.domain.CumulativeSettlementSnapshot;

public interface CumulativeSettlementWorkbookGenerator {

    byte[] generate(CumulativeSettlementSnapshot snapshot);
}
