package com.minipay.ledger.internal.web;

/** Rule: every write API requires an Idempotency-Key header. */
class MissingIdempotencyKeyException extends RuntimeException {

    MissingIdempotencyKeyException() {
        super("The Idempotency-Key header is required on every write request");
    }
}
