package com.minipay.paymenthub.api;

/**
 * Lifecycle of a payment in the hub.
 *
 * <pre>
 * RECEIVED → VALIDATED → SCREENED → FUNDS_RESERVED → SENT → ACKED → SETTLED → (RETURNED)
 *                            └→ HELD_COMPLIANCE ─┘
 * REJECTED: from any state before SENT.
 * </pre>
 *
 * Allowed transitions are defined in one place, {@code PaymentStateMachine}; anything else throws.
 */
public enum PaymentStatus {
    /** Accepted from the channel; nothing checked yet. */
    RECEIVED,
    /** Business validation passed (accounts, currency, identifiers, dates). */
    VALIDATED,
    /** Sanctions screening done with no hit (or a hit that was cleared). */
    SCREENED,
    /** Screening hit: waits for a compliance decision (four-eyes release above the threshold, ADR-007). */
    HELD_COMPLIANCE,
    /** Debtor's funds are on hold in the ledger. Warehoused payments wait here for their date. */
    FUNDS_RESERVED,
    /**
     * Incoming, cover method: we hold the customer payment (pacs.008) and wait for the money to arrive
     * through the correspondent (camt.054 credit notification) before paying the creditor.
     */
    AWAITING_COVER,
    /** Booked in our ledger and handed to the network (pacs.008 to the next agent). */
    SENT,
    /** The next agent accepted it (pacs.002 ACCP / ACSP). */
    ACKED,
    /**
     * Outgoing or forwarded: the creditor's bank confirmed the credit (pacs.002 ACSC). Incoming at the
     * creditor's bank: we credited our customer.
     */
    SETTLED,
    /** Refused, with an ISO reason code: by us before sending, or by a bank further on (pacs.002 RJCT). Final. */
    REJECTED,
    /** Funds came back (pacs.004), e.g. creditor account closed (AC04). Final. */
    RETURNED;

    public boolean isFinal() {
        return this == REJECTED || this == RETURNED;
    }
}
