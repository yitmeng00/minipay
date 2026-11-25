package com.minipay.paymenthub.api;

public enum PaymentError {
    PAYMENT_NOT_FOUND,
    IDEMPOTENCY_KEY_REUSED,
    /** The state machine refused the transition, e.g. RETURNED for a payment that never settled. */
    ILLEGAL_TRANSITION,
    INVALID_NETWORK_EVENT,
    /** An inbound message failed the message checks. It is stored anyway, marked INVALID. */
    INVALID_MESSAGE,
    /** A valid message the hub does not act on (yet), e.g. an inbound pacs.008 before M5. */
    UNSUPPORTED_MESSAGE
}
