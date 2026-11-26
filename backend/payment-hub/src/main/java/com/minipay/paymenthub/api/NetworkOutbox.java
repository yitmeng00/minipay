package com.minipay.paymenthub.api;

import com.minipay.messaging.api.MessageView;
import java.util.UUID;

/**
 * The only way onto the network: the hub's transactional outbox (rule 6). Another module that sends
 * messages, such as a clearing system's operator (ADR-008), hands them over here, inside the transaction
 * that makes them true; the relay publishes them after the commit.
 */
public interface NetworkOutbox {

    /** Must run inside the caller's transaction (MANDATORY): if it rolls back, the message was never sent. */
    void deliver(String aggregateType, UUID aggregateId, MessageView message);

    /** Is this bank one of the simulated institutions with an inbox on the network? */
    boolean isParticipant(String bic);
}
