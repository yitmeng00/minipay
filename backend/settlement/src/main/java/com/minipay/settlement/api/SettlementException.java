package com.minipay.settlement.api;

public class SettlementException extends RuntimeException {

    private final SettlementError error;

    public SettlementException(SettlementError error, String message) {
        super(message);
        this.error = error;
    }

    public SettlementError error() {
        return error;
    }
}
