package com.minipay.ledger.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Turn a hold into real bookings: release the hold and debit the reserved account, crediting each
 * {@link SettlementLeg} (typically the nostro/vostro for the principal and fee income for our charge).
 * Everything is written in one transaction, so there is never a moment where the money is neither
 * held nor booked. The legs must add up to exactly the held amount.
 *
 * @param valueDate null = today's business date in the bank's time zone
 */
public record SettleReservationCommand(
        String idempotencyKey,
        UUID reservationId,
        List<SettlementLeg> legs,
        LocalDate valueDate) {

    public SettleReservationCommand {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(reservationId, "reservationId");
        legs = List.copyOf(Objects.requireNonNull(legs, "legs"));
        if (legs.isEmpty()) {
            throw new IllegalArgumentException("At least one settlement leg is required");
        }
        if (legs.stream().filter(l -> l.amount() == null).count() > 1) {
            throw new IllegalArgumentException("Only one settlement leg may take the remainder");
        }
    }

    /** The whole hold to one account, as a single SETTLE entry. */
    public SettleReservationCommand(String idempotencyKey, UUID reservationId, long creditAccountId,
                                    String narrative, LocalDate valueDate) {
        this(idempotencyKey, reservationId, List.of(SettlementLeg.remainderTo(creditAccountId, narrative)), valueDate);
    }
}
