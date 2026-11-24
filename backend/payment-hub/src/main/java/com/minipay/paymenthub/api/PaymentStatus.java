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
    /** Screening hit — waits for a compliance officer (4-eyes release in M6). */
    HELD_COMPLIANCE,
    /** Debtor's funds are on hold in the ledger. Warehoused payments wait here for their date. */
    FUNDS_RESERVED,
    /** Booked in our ledger and handed to the network (pacs.008 to the next agent). */
    SENT,
    /** The next agent accepted it (pacs.002 ACCP / ACSP). */
    ACKED,
    /** Settlement confirmed: the money has moved between the banks. */
    SETTLED,
    /** Refused before it left us; carries an ISO reason code. Final. */
    REJECTED,
    /** Funds came back after settlement (pacs.004), e.g. creditor account closed (AC04). Final. */
    RETURNED;

    public boolean isFinal() {
        return this == REJECTED || this == RETURNED;
    }
}
