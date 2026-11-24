package com.minipay.ledger.api;

/**
 * Business meaning of a journal entry.
 *
 * <ul>
 *   <li>RESERVE — place a hold (payment reaches FUNDS_RESERVED). RESERVED postings only.</li>
 *   <li>RELEASE — remove a hold, either because the payment failed or because it is being booked.</li>
 *   <li>SETTLE — real movement of funds (BOOKED postings), e.g. customer → nostro.</li>
 *   <li>FEE — a charge booked to fee income.</li>
 *   <li>REVERSAL — the mirror image of an earlier SETTLE/FEE entry. The only way to "undo".</li>
 * </ul>
 */
public enum EntryType {
    RESERVE,
    RELEASE,
    SETTLE,
    FEE,
    REVERSAL
}
