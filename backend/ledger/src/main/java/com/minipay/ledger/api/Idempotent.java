package com.minipay.ledger.api;

/**
 * Result of an idempotent write. {@code replayed = true} means the request was already processed
 * with the same Idempotency-Key and request body, and this is the stored outcome — nothing new was posted.
 */
public record Idempotent<T>(T value, boolean replayed) {

    public static <T> Idempotent<T> created(T value) {
        return new Idempotent<>(value, false);
    }

    public static <T> Idempotent<T> replayed(T value) {
        return new Idempotent<>(value, true);
    }
}
