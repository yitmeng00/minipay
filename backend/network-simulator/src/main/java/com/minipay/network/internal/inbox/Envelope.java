package com.minipay.network.internal.inbox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/** What the sending bank's outbox put on the network: the raw message and its addressing. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Envelope(UUID messageId, String msgId, String type, String sender, String receiver, String uetr, String raw) {
}
