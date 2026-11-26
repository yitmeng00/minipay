package com.minipay.settlement;

import com.minipay.messaging.api.MessageView;
import com.minipay.paymenthub.api.NetworkOutbox;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The operator on its own: settlement with the central bank's ledger, the message store and reference data,
 * against a real PostgreSQL. The network is a recorder: what the operator sends, and that it sent it inside a
 * transaction.
 */
@SpringBootApplication(scanBasePackages = {"com.minipay.ledger", "com.minipay.messaging", "com.minipay.refdata",
        "com.minipay.settlement"})
public class SettlementTestApplication {

    /** Thursday 26 Nov 2026, 10:00 in Frankfurt. */
    public static final Instant NOW = Instant.parse("2026-11-26T09:00:00Z");

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16-alpine");
    }

    @Bean
    TestClock clock() {
        return new TestClock(NOW);
    }

    @Bean
    Network network() {
        return new Network();
    }

    public static final class TestClock extends Clock {
        private volatile Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        public void set(Instant i) {
            now = i;
        }

        public void advance(java.time.Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            TestClock self = this;
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return zone;
                }

                @Override
                public Clock withZone(ZoneId z) {
                    return self.withZone(z);
                }

                @Override
                public Instant instant() {
                    return self.instant();
                }
            };
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    public record Sent(UUID aggregateId, MessageView message) {
    }

    public static final class Network implements NetworkOutbox {
        public final List<Sent> sent = new CopyOnWriteArrayList<>();

        @Override
        public void deliver(String aggregateType, UUID aggregateId, MessageView message) {
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("A message must be handed to the outbox inside a transaction");
            }
            sent.add(new Sent(aggregateId, message));
        }

        @Override
        public boolean isParticipant(String bic) {
            return true;
        }

        public List<MessageView> to(String bic) {
            return sent.stream().map(Sent::message).filter(m -> m.receiverBic().equals(bic)).toList();
        }
    }
}
