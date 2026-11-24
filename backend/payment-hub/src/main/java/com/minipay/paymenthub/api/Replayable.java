package com.minipay.paymenthub.api;

/** Result of an idempotent write: {@code replayed} = same key and request seen before, nothing new done. */
public record Replayable<T>(T value, boolean replayed) {
}
