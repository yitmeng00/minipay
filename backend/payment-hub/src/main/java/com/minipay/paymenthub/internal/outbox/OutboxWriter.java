package com.minipay.paymenthub.internal.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business code's only way to "publish" an event: insert it into the outbox <b>in the caller's
 * transaction</b> ({@code MANDATORY} — calling this outside a transaction is a bug and fails fast).
 *
 * <p>The event becomes visible to the relay only when the business transaction commits; if the
 * transaction rolls back, the event never existed. That is the whole point of the pattern.
 */
@Component
public class OutboxWriter {

    @PersistenceContext
    private EntityManager em;

    private final ObjectMapper json;

    public OutboxWriter(ObjectMapper json) {
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(UUID eventId, String aggregateType, UUID aggregateId, String eventType, String topic,
                       String key, Object payload) {
        try {
            em.persist(new OutboxEvent(eventId, aggregateType, aggregateId, eventType, topic, key,
                    json.writeValueAsString(payload)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise outbox event " + eventType, e);
        }
    }
}
