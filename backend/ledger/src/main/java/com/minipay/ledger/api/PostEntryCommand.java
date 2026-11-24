package com.minipay.ledger.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Post a balanced set of BOOKED lines — a SETTLE (e.g. funding, incoming credit) or FEE entry.
 * RESERVE/RELEASE go through the reservation commands; REVERSAL through {@link ReverseEntryCommand}.
 *
 * @param valueDate null = today's business date in the bank's time zone
 */
public record PostEntryCommand(
        String idempotencyKey,
        EntryType entryType,
        UUID paymentId,
        LocalDate valueDate,
        String narrative,
        List<PostingLine> lines) {

    public PostEntryCommand {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(entryType, "entryType");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
    }
}
