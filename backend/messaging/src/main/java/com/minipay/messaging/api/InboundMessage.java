package com.minipay.messaging.api;

import com.minipay.common.money.Money;

/**
 * Result of receiving a message.
 *
 * @param duplicate      the sender already sent a message with this MsgId: it was stored once, and
 *                       must not be acted on again
 * @param originalUetr   for pacs.002 / pacs.004, the payment it is about
 * @param returnedAmount for pacs.004
 */
public record InboundMessage(
        MessageView message,
        boolean duplicate,
        String originalUetr,
        String status,
        String reasonCode,
        Money returnedAmount,
        String additionalInfo) {

    public boolean valid() {
        return message.errors().isEmpty();
    }
}
