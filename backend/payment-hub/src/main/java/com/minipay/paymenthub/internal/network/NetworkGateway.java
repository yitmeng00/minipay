package com.minipay.paymenthub.internal.network;

import com.minipay.common.id.Bic;
import com.minipay.messaging.api.MessageView;
import com.minipay.messaging.api.MessagingApi;
import com.minipay.paymenthub.internal.outbox.OutboxWriter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Hands messages to the (simulated) network: the bank's side of SWIFT's store-and-forward.
 *
 * <p>A message is <em>not</em> sent from business code. It is written to the transactional outbox, in the
 * same transaction as the status change that produces it (rule 6). The outbox relay then publishes it to
 * the receiver's inbox topic {@code minipay.network.<BIC8>}. So "SENT" and "a pacs.008 is on its way"
 * are one fact: both happen or neither does. Delivery is at least once; the receiving bank drops a
 * redelivery by the event id, and a resend by the sender's MsgId.
 *
 * <p>Only the three simulated banks have an inbox. A message to any other bank (e.g. a payment to Paris)
 * goes to {@code minipay.network.external}: it left us, but nobody in the simulator answers it. The
 * console can play that bank (the network simulation endpoint).
 */
@Component
public class NetworkGateway implements com.minipay.paymenthub.api.NetworkOutbox {

    public static final String EVENT_TYPE = "network.message";
    public static final String EXTERNAL_TOPIC = "minipay.network.external";

    /** What travels on the network: the raw message, and the envelope a gateway reads without parsing it. */
    public record Envelope(UUID messageId, String msgId, String type, String sender, String receiver, String uetr,
                           String raw) {
    }

    private final OutboxWriter outbox;
    private final MessagingApi messaging;
    private final Set<String> participants;

    public NetworkGateway(OutboxWriter outbox, MessagingApi messaging,
                          @Value("${minipay.network.participants:}") List<String> participants) {
        this.outbox = outbox;
        this.messaging = messaging;
        this.participants = participants.stream().filter(s -> !s.isBlank())
                .map(b -> Bic.parse(b).bic8()).collect(Collectors.toUnmodifiableSet());
    }

    public static String inboxTopic(String bic) {
        return "minipay.network." + Bic.parse(bic).bic8();
    }

    /** Is this bank one of the simulated banks, with an inbox on the network? */
    @Override
    public boolean isParticipant(String bic) {
        return bic != null && Bic.isValid(bic) && participants.contains(Bic.parse(bic).bic8());
    }

    public Set<String> participants() {
        return participants;
    }

    /** Puts a stored message on the network. Must run inside the transaction of the status change. */
    public void deliver(UUID paymentId, MessageView m) {
        deliver("payment", paymentId, m);
    }

    @Override
    public void deliver(String aggregateType, UUID aggregateId, MessageView m) {
        String raw = messaging.get(m.id()).raw();
        String topic = isParticipant(m.receiverBic()) ? inboxTopic(m.receiverBic()) : EXTERNAL_TOPIC;
        outbox.append(UUID.randomUUID(), aggregateType, aggregateId, EVENT_TYPE, topic,
                m.uetr() != null ? m.uetr() : m.msgId(),
                new Envelope(m.id(), m.msgId(), m.type().identifier(), m.senderBic(), m.receiverBic(), m.uetr(), raw));
    }
}
