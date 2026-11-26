package com.minipay.settlement.api;

/**
 * A payment order at the clearing system.
 * <ul>
 *   <li>QUEUED: RTGS, waiting for liquidity (or for its settlement date)</li>
 *   <li>ACCEPTED: DNS, in a cycle that has not settled yet</li>
 *   <li>PENDING: instant, the payer's position is reserved, waiting for the creditor's bank to answer</li>
 *   <li>SETTLED: final and irrevocable: the money moved on the central bank's books</li>
 *   <li>REJECTED: final; nothing moved</li>
 * </ul>
 */
public enum TransferStatus {
    QUEUED, ACCEPTED, PENDING, SETTLED, REJECTED;

    public boolean isFinal() {
        return this == SETTLED || this == REJECTED;
    }
}
