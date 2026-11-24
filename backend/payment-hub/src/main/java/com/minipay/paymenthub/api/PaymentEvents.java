package com.minipay.paymenthub.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Events the payment hub publishes (through the outbox) to Kafka topic
 * {@code minipay.<bank-bic>.payment-events}, keyed by UETR.
 *
 * <p>Delivery is <b>at least once</b>: a consumer may see the same event twice (e.g. after a relay
 * crash) and must de-duplicate by {@code eventId}, which is also sent as the Kafka header
 * {@code event-id}.
 */
public final class PaymentEvents {

    public static final String TOPIC_SUFFIX = ".payment-events";
    public static final String STATUS_CHANGED = "payment.status-changed";

    private PaymentEvents() {
    }

    /** Emitted for every status change, with a full snapshot so consumers never need to call back. */
    public record StatusChanged(
            UUID eventId,
            String eventType,
            String bankBic,
            PaymentStatus from,
            PaymentStatus to,
            String reasonCode,
            Instant occurredAt,
            PaymentView payment) {
    }
}
