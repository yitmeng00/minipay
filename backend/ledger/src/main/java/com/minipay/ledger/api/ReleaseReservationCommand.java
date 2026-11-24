package com.minipay.ledger.api;

import java.util.Objects;
import java.util.UUID;

/** Release a hold without moving money, e.g. the payment was rejected or cancelled. */
public record ReleaseReservationCommand(String idempotencyKey, UUID reservationId, String reason) {

    public ReleaseReservationCommand {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(reservationId, "reservationId");
    }
}
