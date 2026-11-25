package com.minipay.refdata.api;

/** Result of an idempotent write; {@code replayed = true} means the stored outcome of an earlier identical request. */
public record Idempotent<T>(T value, boolean replayed) {
}
