package com.tieat.ledger.application;

public interface ConfirmedMealUsageWorkbookGenerator {

    byte[] generate(ConfirmedMealUsageExportSnapshot snapshot);
}
