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

    /** Builds, checks and stores an outgoing pacs.009 COV (the money leg of a cover payment). Idempotent per key. */
    MessageView issuePacs009(String businessKey, CoverTransfer cover);

    /** Builds, checks and stores a pacs.002 we send. Idempotent per key. */
    MessageView issuePacs002(String businessKey, StatusReport report);

    /** Builds, checks and stores a pacs.004 we send. Idempotent per key. */
    MessageView issuePacs004(String businessKey, PaymentReturn paymentReturn);

    /** Builds, checks and stores a camt.054 credit notification we send. Idempotent per key. */
    MessageView issueCamt054(String businessKey, CreditNotification notification);

    /** The pacs.008 this transfer would produce, without storing anything (a preview). */
    String renderPacs008(CreditTransfer transfer);

    /** Builds a pacs.008 with a fresh MsgId (not stored), e.g. to play another bank in a simulation or a test. */
    String buildPacs008(CreditTransfer transfer);

    /** Builds a pacs.009 COV (not stored). */
    String buildPacs009(CoverTransfer cover);

    /** Builds a camt.054 credit notification (not stored). */
    String buildCamt054(CreditNotification notification);

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

    /** The customer credit transfer in a stored pacs.008. */
    CreditTransfer creditTransfer(UUID messageId);

    /** The cover transfer in a stored pacs.009. */
    CoverTransfer coverTransfer(UUID messageId);

    /** The return in a stored pacs.004. */
    PaymentReturn paymentReturn(UUID messageId);

    /** The notification in a stored camt.054. */
    CreditNotification creditNotification(UUID messageId);

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
