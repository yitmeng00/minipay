package com.minipay.ledger.api;

/** ACTIVE → RELEASED (payment failed or cancelled) or ACTIVE → SETTLED (payment booked). Both end states are final. */
public enum ReservationStatus {
    ACTIVE,
    RELEASED,
    SETTLED
}
