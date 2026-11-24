package com.minipay.ledger.api;

/** Debit or credit. Single letters match the {@code posting.direction} column and ISO 20022 {@code CdtDbtInd} (DBIT/CRDT). */
public enum Direction {
    D,
    C;

    public Direction opposite() {
        return this == D ? C : D;
    }
}
