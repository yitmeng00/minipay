package com.minipay.messaging.api;

/** A stored message with its raw text, byte for byte as it was sent or received. */
public record MessageDetail(MessageView message, String raw) {
}
