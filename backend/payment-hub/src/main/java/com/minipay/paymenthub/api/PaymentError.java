package com.minipay.paymenthub.api;

public enum PaymentError {
    PAYMENT_NOT_FOUND,
    IDEMPOTENCY_KEY_REUSED,
    /** The state machine refused the transition, e.g. RETURNED for a payment that never settled. */
    ILLEGAL_TRANSITION,
    INVALID_NETWORK_EVENT
}
