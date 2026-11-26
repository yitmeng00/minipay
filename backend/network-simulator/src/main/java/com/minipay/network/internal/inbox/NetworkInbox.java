package com.minipay.network.internal.inbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.paymenthub.api.PaymentException;
import com.minipay.paymenthub.api.PaymentHubApi;
import com.minipay.settlement.api.SettlementException;
import com.minipay.settlement.api.SettlementOperatorApi;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * This bank's side of the network: every message addressed to it is handed to the payment hub, which
 * stores it, checks it and acts on it.
 *
 * <p>The network delivers at least once. A redelivery carries the same event id, which becomes the
 * Idempotency-Key ({@code net:<event id>}), so it replays. A resend by the sender (a new event, the same
 * MsgId) is caught by the message store. A message the hub refuses (invalid, for an unknown payment,
 * out of order) is already stored as evidence: there is nothing to retry, so it is logged and the inbox
 * moves on. Anything else (the database is down) is retried by the container's error handler.
 */
@Component
@ConditionalOnProperty(name = "minipay.network.enabled", havingValue = "true", matchIfMissing = true)
class NetworkInbox {

    private static final Logger log = LoggerFactory.getLogger(NetworkInbox.class);

    private final PaymentHubApi hub;
    private final SettlementOperatorApi operator;
    private final ObjectMapper json;

    NetworkInbox(PaymentHubApi hub, SettlementOperatorApi operator, ObjectMapper json) {
        this.hub = hub;
        this.operator = operator;
        this.json = json;
    }

    @KafkaListener(id = "network-inbox", topics = "#{networkNames.inbox()}", groupId = "#{networkNames.inboxGroup()}")
    void onMessage(ConsumerRecord<String, String> record) throws Exception {
        Envelope e = json.readValue(record.value(), Envelope.class);
        String eventId = header(record, "event-id");
        if (operator.isOperator()) {
            // The central bank's inbox: payment orders for its clearing systems (ADR-008). A redelivery is the
            // same message, which the message store recognises by (sender, MsgId).
            try {
                var receipt = operator.receive(e.raw());
                log.info("{} {} from {} → transfer {} {}{}", e.type(), e.msgId(), e.sender(), receipt.transferId(),
                        receipt.status(), receipt.replayed() ? " (already processed)" : "");
            } catch (SettlementException ex) {
                log.info("{} {} from {} stored, not acted on: {}", e.type(), e.msgId(), e.sender(), ex.getMessage());
            }
            return;
        }
        try {
            var result = hub.receiveMessage("net:" + eventId, e.raw());
            log.info("{} {} from {} → payment {} {}{}", e.type(), e.msgId(), e.sender(), result.value().uetr(),
                    result.value().status(), result.replayed() ? " (already processed)" : "");
        } catch (PaymentException ex) {
            log.info("{} {} from {} stored, not acted on: {}", e.type(), e.msgId(), e.sender(), ex.getMessage());
        }
    }

    static String header(ConsumerRecord<?, ?> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
