package com.minipay.refdata.api;

/** How a payment reaches the creditor agent. */
public enum RoutingMethod {
    /** We send to the creditor agent and settle on our bilateral account with it (nostro or vostro). */
    DIRECT,
    /** We send to a correspondent (the next hop), who passes the payment on; we settle with the correspondent. */
    SERIAL,
    /** We send the payment to the creditor agent, and the money separately through a correspondent (pacs.009 COV). */
    COVER,
    /**
     * Real-time gross settlement in central bank money. If the currency's RTGS is a simulated clearing system,
     * the pacs.008 goes to its operator (ADR-008); otherwise straight to the creditor agent (M3–M6).
     */
    RTGS,
    /** Deferred net settlement through the currency's ACH: collected in cycles, netted, settled on the RTGS. */
    DNS,
    /** Instant payment through the currency's instant system: settled within seconds on prefunded positions, or rejected. */
    INSTANT;

    /** Does this method go through a correspondent we must name? */
    public boolean needsNextHop() {
        return this == SERIAL || this == COVER;
    }

    /** Is this the kind of a clearing system (a payment of this method goes through a system's operator)? */
    public boolean isClearing() {
        return this == RTGS || this == DNS || this == INSTANT;
    }
}
