package com.minipay.ledger.api;

/**
 * ACTIVE accounts accept postings. BLOCKED accounts (e.g. a court order or fraud freeze) may
 * still receive credits but not be debited (ISO reason AC06). CLOSED accounts accept nothing (AC04).
 */
public enum AccountStatus {
    ACTIVE,
    BLOCKED,
    CLOSED
}
