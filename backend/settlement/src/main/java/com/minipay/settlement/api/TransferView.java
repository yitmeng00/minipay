package com.minipay.settlement.api;

import com.minipay.common.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One payment order a participant sent to a clearing system.
 *
 * @param kind        RTGS, DNS or INSTANT
 * @param messageType pacs.008 (a payment) or pacs.004 (a return, settled like a payment)
 * @param messageId   the stored message the participant sent
 * @param priority    HIGH (urgent) or NORM
 * @param queueSeq    RTGS: its place in the payer's queue (lower goes first, within a priority)
 * @param deadline    INSTANT: when it times out
 * @param waiting     QUEUED: why it has not settled yet (computed, not stored)
 */
public record TransferView(
        UUID id,
        String system,
        String kind,
        String messageType,
        UUID messageId,
        String msgId,
        UUID uetr,
        String endToEndId,
        String instructionId,
        String payerBic,
        String payeeBic,
        Money amount,
        String priority,
        LocalDate settlementDate,
        TransferStatus status,
        long queueSeq,
        String cycleId,
        Instant deadline,
        String reasonCode,
        String reasonDetail,
        Instant receivedAt,
        Instant settledAt,
        String waiting) {

    public TransferView withWaiting(String why) {
        return new TransferView(id, system, kind, messageType, messageId, msgId, uetr, endToEndId, instructionId, payerBic, payeeBic, amount, priority,
                settlementDate, status, queueSeq, cycleId, deadline, reasonCode, reasonDetail, receivedAt, settledAt, why);
    }
}
