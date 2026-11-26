package com.minipay.settlement.api;

/** A command's result, and whether it is the replay of an earlier one with the same Idempotency-Key (rule 5). */
public record Replayed<T>(T value, boolean replayed) {
}
