package com.minipay.network.internal.tracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.messaging.api.MessageSummary;
import com.minipay.messaging.api.MessagingApi;
import com.minipay.network.api.TrackedMessage;
import com.minipay.network.internal.inbox.Envelope;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Feeds the tracker: reads every topic of the network (all banks' inboxes, and the external one) and
 * records what each message says. It only reads the messages; it never stores them as received, and it
 * never acts on them — it is an observer, like the gpi Tracker.
 */
@Component
@ConditionalOnProperty(name = "minipay.network.enabled", havingValue = "true", matchIfMissing = true)
class TrackerRecorder {

    private final MessagingApi messaging;
    private final TrackerRepository repo;
    private final ObjectMapper json;

    TrackerRecorder(MessagingApi messaging, TrackerRepository repo, ObjectMapper json) {
        this.messaging = messaging;
        this.repo = repo;
        this.json = json;
    }

    @KafkaListener(id = "network-tracker", topicPattern = "minipay\\.network\\..*", groupId = "#{networkNames.trackerGroup()}")
    void onMessage(ConsumerRecord<String, String> record) throws Exception {
        Envelope e = json.readValue(record.value(), Envelope.class);
        MessageSummary s = messaging.inspect(e.raw());
        if (s.uetr() == null || s.type() == null) {
            return; // nothing to track it by
        }
        Header id = record.headers().lastHeader("event-id");
        UUID eventId = id != null ? UUID.fromString(new String(id.value(), StandardCharsets.UTF_8)) : UUID.randomUUID();
        repo.record(new TrackedMessage(eventId, s.type().identifier(), s.msgId(), s.senderBic(), s.receiverBic(),
                s.originalMsgId(), s.amount(), s.settlementMethod(), s.status(), s.reasonCode(), s.charges(),
                Instant.ofEpochMilli(record.timestamp())), UUID.fromString(s.uetr()));
    }
}
