package com.minipay.paymenthub.internal.processing;

/** Why a payment is refused: an ISO 20022 ExternalStatusReason1Code plus a human-readable detail. */
public record Rejection(String reasonCode, String detail) {
}
