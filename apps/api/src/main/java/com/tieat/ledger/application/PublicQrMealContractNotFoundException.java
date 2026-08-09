package com.tieat.ledger.application;

public final class PublicQrMealContractNotFoundException extends RuntimeException {

    public PublicQrMealContractNotFoundException() {
        super("QR-selectable meal contract was not found");
    }
}
