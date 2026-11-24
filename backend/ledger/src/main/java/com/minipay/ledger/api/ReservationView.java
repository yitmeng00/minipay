package com.minipay.ledger.api;

import com.minipay.common.money.Money;
import java.time.Instant;
import java.util.UUID;

public record ReservationView(
        UUID id,
        long accountId,
        Money amount,
        UUID paymentId,
        String reason,
        ReservationStatus status,
        UUID reserveEntryId,
        UUID releaseEntryId,
        UUID settleEntryId,
        Instant createdAt,
        Instant updatedAt) {
}
