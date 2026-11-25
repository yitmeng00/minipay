package com.minipay.messaging.api;

/** Result of an idempotent write; {@code replayed = true} means an earlier identical request's stored result. */
public record Idempotent<T>(T value, boolean replayed) {
}
