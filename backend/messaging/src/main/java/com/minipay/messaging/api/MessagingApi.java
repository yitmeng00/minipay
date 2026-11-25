package com.minipay.messaging.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The bank's message gateway. Every message that goes out or comes in is stored exactly as it was,
 * with the fields parsed from it (rule 8).
 */
public interface MessagingApi {

    /**
     * Builds, checks and stores an outgoing pacs.008. Idempotent per {@code businessKey}: a second call
     * returns the message stored by the first, even if the transfer would now be built differently,
     * because a message, once sent, is a fact.
     */
    MessageView issuePacs008(String businessKey, CreditTransfer transfer);

    /** The pacs.008 this transfer would produce, without storing anything (a preview). */
    String renderPacs008(CreditTransfer transfer);

    /** Builds a pacs.002 (not stored: the caller decides whether it is sent or received). */
    String buildPacs002(StatusReport report);

    /** Builds a pacs.004 (not stored). */
    String buildPacs004(PaymentReturn paymentReturn);

    /**
     * Receives a message: parses it, checks it, stores it. An invalid message is stored too, marked
     * INVALID with its errors. A message whose sender already used its MsgId is a duplicate: it is
     * not stored again, and {@link InboundMessage#duplicate()} tells the caller not to act on it.
     */
    InboundMessage receive(String raw);

    MessageDetail get(UUID id);

    /** Every message about one payment, oldest first. */
    List<MessageView> forUetr(String uetr);

    /** Most recent first. */
    List<MessageView> recent(MessageType type, int limit);

    /** The MT103 a stored pacs.008 translates to, with the field-by-field report. */
    Translation toMt103(UUID pacs008MessageId);

    /** The pacs.008 an MT103 translates to, with the report (not stored). */
    Translation fromMt103(String fin);

    /**
     * Issues and stores a camt.053 end-of-day statement for an account we keep for someone else (a
     * customer, or another bank's vostro), from the ledger statement of that business date.
     */
    Idempotent<MessageView> issueCamt053(String idempotencyKey, long accountId, LocalDate date);
}
