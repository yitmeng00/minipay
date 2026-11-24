package com.minipay.ledger.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record JournalEntryView(
        UUID id,
        EntryType entryType,
        UUID paymentId,
        UUID reversesEntryId,
        LocalDate valueDate,
        String narrative,
        Instant createdAt,
        List<PostingView> postings) {
}
