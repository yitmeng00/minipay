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

    /**
     * SIMULATION (until M5): plays the next bank and sends us its pacs.002 / pacs.004, which is then
     * received like any real message. Idempotent per key.
     */
    Replayable<PaymentView> onNetworkEvent(String idempotencyKey, String uetr, NetworkEvent event);

    /**
     * Receives a message from another bank about a payment we sent (pacs.002 status, pacs.004 return).
     * The raw message is stored whatever happens; a duplicate (same sender and MsgId) changes nothing.
     */
    Replayable<PaymentView> receiveMessage(String idempotencyKey, String raw);

    /** The pacs.008 this payment would produce if sent now; nothing is created, held or booked. */
    PaymentPreview preview(CreatePaymentCommand command);
}
