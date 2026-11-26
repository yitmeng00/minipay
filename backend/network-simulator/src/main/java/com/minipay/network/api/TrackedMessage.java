package com.minipay.network.api;

import com.minipay.common.money.Money;
import com.minipay.messaging.api.Charge;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One message the network carried for a payment, as the tracker saw it. */
public record TrackedMessage(
        UUID eventId,
        String type,
        String msgId,
        String fromBic,
        String toBic,
        String originalMsgId,
        Money amount,
        String settlementMethod,
        String status,
        String reasonCode,
        List<Charge> charges,
        Instant sentAt) {
}
