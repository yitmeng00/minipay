package com.minipay.paymenthub.internal.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves committed outbox rows to Kafka. The only class in the code base allowed to touch Kafka
 * (ArchUnit rule 6).
 *
 * <h2>Algorithm (one batch)</h2>
 * <ol>
 *   <li>In a transaction, lock the oldest unpublished rows: {@code ORDER BY seq ... FOR UPDATE SKIP LOCKED}.
 *       SKIP LOCKED lets a second relay instance take the <em>next</em> rows instead of waiting.</li>
 *   <li>Send each to Kafka and <b>wait for the broker's ack</b> (acks=all, idempotent producer).</li>
 *   <li>Mark sent rows {@code published_at = now()} and commit.</li>
 * </ol>
 *
 * <h2>Guarantee: at least once, in order per payment</h2>
 * If the process dies after step 2 but before step 3 commits, the rows are still unpublished and are
 * sent again on restart — so consumers can see an event twice and must de-duplicate by the
 * {@code event-id} header. The relay never marks a row published that Kafka did not ack, so nothing is
 * lost. On the first failure the batch stops: later events of the same payment are not sent ahead of
 * an earlier one.
 */
@Component
@ConditionalOnProperty(name = "minipay.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final int batchSize;
    private final Duration sendTimeout;
    private volatile CrashHook crashHook = CrashHook.NONE;

    /** Test seam: simulates the process dying at a chosen point of the relay algorithm. */
    public interface CrashHook {
        CrashHook NONE = () -> { };

        void afterSendBeforeMarkPublished();
    }

    public OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, PlatformTransactionManager txManager,
                       @Value("${minipay.outbox.relay.batch-size:100}") int batchSize,
                       @Value("${minipay.outbox.relay.send-timeout:PT10S}") Duration sendTimeout) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = new TransactionTemplate(txManager);
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
    }

    void setCrashHook(CrashHook hook) {
        this.crashHook = hook == null ? CrashHook.NONE : hook;
    }

    record Pending(UUID id, long seq, String topic, String key, String eventType, UUID aggregateId, String payload) {
    }

    @Scheduled(fixedDelayString = "${minipay.outbox.relay.interval:PT0.5S}")
    public void poll() {
        try {
            while (publishBatch() == batchSize) {
                // keep draining while there is a backlog
            }
        } catch (RuntimeException e) {
            log.warn("Outbox relay batch failed, will retry: {}", e.toString());
        }
    }

    /** Publishes one batch. Returns how many events were published. */
    public int publishBatch() {
        Integer published = tx.execute(status -> {
            List<Pending> batch = jdbc.sql("""
                            SELECT id, seq, topic, message_key, event_type, aggregate_id, payload::text AS payload
                              FROM payment.outbox_event
                             WHERE published_at IS NULL
                             ORDER BY seq
                             LIMIT :limit
                               FOR UPDATE SKIP LOCKED
                            """)
                    .param("limit", batchSize)
                    .query((rs, n) -> new Pending(rs.getObject("id", UUID.class), rs.getLong("seq"),
                            rs.getString("topic"), rs.getString("message_key"), rs.getString("event_type"),
                            rs.getObject("aggregate_id", UUID.class), rs.getString("payload")))
                    .list();
            int sent = 0;
            for (Pending e : batch) {
                try {
                    ProducerRecord<String, String> record = new ProducerRecord<>(e.topic(), e.key(), e.payload());
                    record.headers().add("event-id", e.id().toString().getBytes(StandardCharsets.UTF_8));
                    record.headers().add("event-type", e.eventType().getBytes(StandardCharsets.UTF_8));
                    record.headers().add("aggregate-id", e.aggregateId().toString().getBytes(StandardCharsets.UTF_8));
                    kafka.send(record).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
                } catch (Exception ex) {
                    recordFailure(e.id(), ex);
                    break; // keep ordering: do not send later events ahead of this one
                }
                sent++;
            }
            if (sent > 0) {
                crashHook.afterSendBeforeMarkPublished();
                jdbc.sql("UPDATE payment.outbox_event SET published_at = clock_timestamp(), attempts = attempts + 1 WHERE id IN (:ids)")
                        .param("ids", batch.subList(0, sent).stream().map(Pending::id).toList())
                        .update();
            }
            return sent;
        });
        return published == null ? 0 : published;
    }

    private void recordFailure(UUID id, Exception ex) {
        String message = ex.getCause() != null ? ex.getCause().toString() : ex.toString();
        log.warn("Kafka send failed for outbox event {}: {}", id, message);
        // Same transaction as the batch (it still commits: earlier rows of the batch are marked published).
        // A separate transaction would wait for the row lock this transaction holds.
        jdbc.sql("""
                UPDATE payment.outbox_event SET attempts = attempts + 1, last_error = left(:err, 1000) WHERE id = :id
                """).param("err", message).param("id", id).update();
    }
}
