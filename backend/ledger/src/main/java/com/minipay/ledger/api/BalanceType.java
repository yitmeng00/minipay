package com.minipay.ledger.api;

/**
 * Which balance a posting moves.
 *
 * <ul>
 *   <li>BOOKED — money actually moved. Appears on the customer's statement (camt.053 {@code BOOK}).</li>
 *   <li>RESERVED — funds earmarked by a hold; reduces <em>available</em> but not <em>booked</em>.
 *       A customer never sees these lines on a statement.</li>
 * </ul>
 */
public enum BalanceType {
    BOOKED,
    RESERVED
}
