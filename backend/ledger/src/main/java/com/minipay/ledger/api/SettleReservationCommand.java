package com.minipay.ledger.api;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Turn a hold into a real booking: release the hold and debit the reserved account, crediting
 * {@code creditAccountId} (typically the nostro at the correspondent, or a vostro). Both entries are
 * written in one transaction, so there is never a moment where the money is neither held nor booked.
 *
 * @param valueDate null = today's business date in the bank's time zone
 */
public record SettleReservationCommand(
        String idempotencyKey,
        UUID reservationId,
        long creditAccountId,
        String narrative,
        LocalDate valueDate) {

    public SettleReservationCommand {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(reservationId, "reservationId");
    }
}
