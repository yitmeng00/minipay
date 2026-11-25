package com.minipay.refdata.api;

/** How a payment reaches the creditor agent. */
public enum RoutingMethod {
    /** We send to the creditor agent and settle on our bilateral account with it (nostro or vostro). */
    DIRECT,
    /** We send to a correspondent (the next hop), who passes the payment on; we settle with the correspondent. */
    SERIAL,
    /** We send the payment to the creditor agent, and the money separately through a correspondent (pacs.009 COV). */
    COVER,
    /** We send to the creditor agent through the domestic RTGS system and settle on our central bank account. */
    RTGS;

    /** Does this method go through a correspondent we must name? */
    public boolean needsNextHop() {
        return this == SERIAL || this == COVER;
    }
}
