package com.minipay.messaging.api;

import com.minipay.common.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A stored message: the fields parsed from it. The raw text is in {@link MessageDetail}.
 *
 * @param type        null if an inbound message could not even be identified
 * @param status      pacs.002 TxSts (ACCP, ACSC, RJCT …), RTND for a return, or INVALID for an
 *                    inbound message that failed the checks
 * @param errors      why an inbound message is INVALID; empty otherwise
 * @param sha256      hash of the raw text; the database refuses a row whose raw text does not match it
 */
public record MessageView(
        UUID id,
        MessageDirection direction,
        MessageType type,
        String msgId,
        String senderBic,
        String receiverBic,
        String uetr,
        String endToEndId,
        String instructionId,
        String originalMsgId,
        String account,
        Money amount,
        LocalDate settlementDate,
        String status,
        String reasonCode,
        List<String> errors,
        String sha256,
        Instant createdAt) {
}
