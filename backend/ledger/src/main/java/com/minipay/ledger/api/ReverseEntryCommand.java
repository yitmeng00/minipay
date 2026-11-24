package com.minipay.ledger.api;

import java.util.Objects;
import java.util.UUID;

/** Reverse an earlier SETTLE or FEE entry by posting its mirror image. The original stays untouched. */
public record ReverseEntryCommand(String idempotencyKey, UUID entryId, String narrative) {

    public ReverseEntryCommand {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(entryId, "entryId");
    }
}
