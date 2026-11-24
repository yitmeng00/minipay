package com.minipay.ledger.api;

/** A ledger request was refused. Carries a {@link LedgerError} with its ISO reason code. */
public class LedgerException extends RuntimeException {

    private final LedgerError error;

    public LedgerException(LedgerError error, String message) {
        super(message);
        this.error = error;
    }

    public LedgerError error() {
        return error;
    }
}
