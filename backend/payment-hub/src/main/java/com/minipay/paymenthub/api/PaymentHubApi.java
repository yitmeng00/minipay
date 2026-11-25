package com.minipay.paymenthub.api;

import java.time.LocalDate;
import java.util.Map;

/**
 * Public contract of the payment hub. Payments are addressed by UETR — the identifier every bank on
 * the route shares.
 */
public interface PaymentHubApi {

    /**
     * Accepts a payment and drives it as far as it can go synchronously
     * (normally SENT or REJECTED; FUNDS_RESERVED if warehoused for a future date).
     */
    Replayable<PaymentView> create(CreatePaymentCommand command);

    /** @param ref the UETR, or the hub's internal payment id (as stored on ledger entries) */
    PaymentDetailView get(String ref);

    PageView<PaymentView> search(PaymentQuery query);

    Map<PaymentStatus, Long> countByStatus();

    /** The dashboard figures for one business date (bank time zone). */
    DailyOverview overview(LocalDate date);

    /** Applies a network outcome (ack, settlement, return). Idempotent per key. */
    Replayable<PaymentView> onNetworkEvent(String idempotencyKey, String uetr, NetworkEvent event);
}
