package com.minipay.paymenthub.api;

import java.time.Instant;

/** One row of the payment's audit trail. {@code from} is null for the initial RECEIVED. */
public record StateChangeView(
        long id,
        PaymentStatus from,
        PaymentStatus to,
        String reasonCode,
        String detail,
        String actor,
        Instant occurredAt) {
}
