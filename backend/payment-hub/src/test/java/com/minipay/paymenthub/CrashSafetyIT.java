package com.minipay.paymenthub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.common.iso.ChargeBearer;
import com.minipay.ledger.api.AccountView;
import com.minipay.ledger.api.EntryType;
import com.minipay.ledger.api.JournalEntryView;
import com.minipay.paymenthub.api.PaymentStatus;
import com.minipay.paymenthub.api.PaymentView;
import com.minipay.paymenthub.api.StateChangeView;
import com.minipay.paymenthub.internal.outbox.KafkaTopicsConfiguration;
import com.minipay.paymenthub.internal.outbox.OutboxRelay;
import com.minipay.paymenthub.internal.outbox.OutboxRelayTestAccess;
import com.minipay.paymenthub.internal.outbox.OutboxWriter;
import com.minipay.paymenthub.internal.processing.PaymentProcessor;
import com.minipay.paymenthub.internal.processing.PaymentProcessorTestAccess;
import com.minipay.paymenthub.internal.processing.RecoverySweeper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

/**
 * What happens when things die at the worst moment. Each test names the failure it simulates.
 */
class CrashSafetyIT extends PaymentHubIntegrationTest {

    @Autowired
    PaymentProcessor processor;

    @Autowired
    RecoverySweeper sweeper;

    @Autowired
    OutboxRelay relay;

    @Autowired
    OutboxWriter outbox;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager txManager;

    @Autowired
    KafkaContainer kafkaContainer;

    @Autowired
    ObjectMapper json;

    @Autowired
    com.minipay.messaging.api.MessagingApi messaging;

    // ------------------------------------------------------------------ pipeline crashes

    @Test
    void a_crash_mid_pipeline_leaves_the_payment_stored_and_the_sweeper_finishes_it() {
        AccountView debtor = customer("EUR", "1000.00");
        screening.crash(true); // the process "dies" while screening

        PaymentView p = hub.create(payment(debtor, "100.00", ChargeBearer.SHAR)).value();
        assertThat(p.status()).isEqualTo(PaymentStatus.VALIDATED); // stored, not lost
        assertThat(reload(debtor).reserved().isZero()).isTrue();

        screening.crash(false); // "restart"
        clock.advance(Duration.ofMinutes(5)); // older than recovery-stale-after
        try {
            sweeper.resumeStuckPayments();
        } finally {
            clock.advance(Duration.ofMinutes(-5));
        }

        assertThat(hub.get(p.uetr()).payment().status()).isEqualTo(PaymentStatus.SENT);
        assertThat(reload(debtor).booked()).isEqualTo(eur("880.00"));
    }

    @Test
    void racing_workers_on_one_payment_book_it_exactly_once() throws Exception {
        AccountView debtor = customer("EUR", "1000.00");
        // Warehoused for tomorrow: it waits at FUNDS_RESERVED, so all workers will race on the
        // ledger booking itself (the step that moves money), not on an earlier step.
        PaymentView p = hub.create(withDate(payment(debtor, "100.00", ChargeBearer.SHAR),
                java.time.LocalDate.of(2026, 11, 25))).value();
        assertThat(p.status()).isEqualTo(PaymentStatus.FUNDS_RESERVED);
        clock.advance(Duration.ofDays(1));

        // Ten workers (e.g. the sweeper on several nodes plus a retry) pick up the same payment at once.
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> runs = new ArrayList<>();
        try {
            for (int i = 0; i < 10; i++) {
                runs.add(pool.submit(() -> {
                    go.await();
                    processor.process(p.id());
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : runs) {
                f.get(60, TimeUnit.SECONDS); // no worker may fail
            }
        } finally {
            pool.shutdown();
            clock.advance(Duration.ofDays(-1));
        }

        // Exactly one of each ledger entry — the idempotency keys made every repeat a replay ...
        assertThat(ledger.entriesForPayment(p.id())).extracting(JournalEntryView::entryType)
                .containsExactly(EntryType.RESERVE, EntryType.RELEASE, EntryType.SETTLE, EntryType.FEE);
        assertThat(reload(debtor).booked()).isEqualTo(eur("880.00"));
        // ... and exactly one of each transition — the expected-status check / @Version stopped duplicates.
        assertThat(hub.get(p.uetr()).history()).extracting(StateChangeView::to).containsExactly(
                PaymentStatus.RECEIVED, PaymentStatus.VALIDATED, PaymentStatus.SCREENED,
                PaymentStatus.FUNDS_RESERVED, PaymentStatus.SENT);
    }

    @Test
    void a_crash_after_booking_is_replayed_with_the_same_value_date_even_after_the_cut_off() {
        // The ledger request includes the value date. If the retry recomputed it after the cut-off,
        // it would ask for a different booking under the same key, and the ledger would refuse.
        AccountView debtor = customer("MYR", "5000.00");
        PaymentProcessorTestAccess.crashAfterLedgerBooking(processor);
        PaymentView p;
        try {
            p = hub.create(new com.minipay.paymenthub.api.CreatePaymentCommand(key(), debtor.accountNumber(),
                    "Kessler Maschinenbau GmbH", "DE13500100600412280017", BANK_B,
                    com.minipay.common.money.Money.of("250.00", "MYR"), ChargeBearer.SHAR, null, null, null)).value();
        } finally {
            PaymentProcessorTestAccess.noCrash(processor);
        }
        assertThat(p.status()).isEqualTo(PaymentStatus.FUNDS_RESERVED); // booked in the ledger, SENT not recorded
        assertThat(ledger.entriesForPayment(p.id())).extracting(JournalEntryView::entryType).contains(EntryType.SETTLE);

        clock.advance(Duration.ofHours(9)); // restart at 19:00 Kuala Lumpur: RENTAS closed at 18:00
        try {
            sweeper.resumeStuckPayments();
        } finally {
            clock.advance(Duration.ofHours(-9));
        }

        PaymentView sent = hub.get(p.uetr()).payment();
        assertThat(sent.status()).isEqualTo(PaymentStatus.SENT);
        assertThat(sent.valueDate()).isEqualTo(java.time.LocalDate.of(2026, 11, 24)); // the booked one, not tomorrow
        assertThat(ledger.entriesForPayment(p.id())).extracting(JournalEntryView::entryType)
                .containsExactly(EntryType.RESERVE, EntryType.RELEASE, EntryType.SETTLE);
    }

    @Test
    void racing_workers_send_one_pacs008_per_payment() throws Exception {
        PaymentView p = hub.create(withDate(payment(customer("EUR", "1000.00"), "100.00", ChargeBearer.SHAR),
                java.time.LocalDate.of(2026, 11, 25))).value();
        clock.advance(Duration.ofDays(1));
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> runs = new ArrayList<>();
        try {
            for (int i = 0; i < 10; i++) {
                runs.add(pool.submit(() -> {
                    go.await();
                    processor.process(p.id());
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : runs) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
            clock.advance(Duration.ofDays(-1));
        }
        assertThat(hub.get(p.uetr()).payment().status()).isEqualTo(PaymentStatus.SENT);
        assertThat(messaging.forUetr(p.uetr())).hasSize(1); // one payment, one message on the wire
    }

    // ------------------------------------------------------------------ outbox

    @Test
    void an_event_written_in_a_rolled_back_transaction_never_exists() {
        UUID eventId = UUID.randomUUID();
        assertThatThrownBy(() -> new TransactionTemplate(txManager).executeWithoutResult(s -> {
            outbox.append(eventId, "payment", UUID.randomUUID(), "test", "minipay.test", "k", Map.of("x", 1));
            throw new IllegalStateException("business failure after writing the event");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.sql("SELECT count(*) FROM payment.outbox_event WHERE id = :id").param("id", eventId)
                .query(Long.class).single()).isZero();
    }

    @Test
    void the_outbox_can_only_be_written_inside_a_business_transaction() {
        assertThatThrownBy(() -> outbox.append(UUID.randomUUID(), "payment", UUID.randomUUID(), "test", "t", "k", Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void kafka_outage_does_not_block_payments_and_loses_no_event() throws Exception {
        relay.publishBatch(); // drain anything earlier tests left
        String containerId = kafkaContainer.getContainerId();
        var docker = kafkaContainer.getDockerClient();

        docker.pauseContainerCmd(containerId).exec();
        PaymentView p;
        try {
            // The business keeps working while the broker is down ...
            p = hub.create(payment(customer("EUR", "1000.00"), "100.00", ChargeBearer.SHAR)).value();
            assertThat(p.status()).isEqualTo(PaymentStatus.SENT);
            // ... the relay cannot publish, and records why.
            assertThat(relay.publishBatch()).isZero();
            assertThat(jdbc.sql("""
                    SELECT count(*) FROM payment.outbox_event
                     WHERE aggregate_id = :id AND published_at IS NULL
                    """).param("id", p.id()).query(Long.class).single()).isEqualTo(5L);
            assertThat(jdbc.sql("SELECT max(attempts) FROM payment.outbox_event WHERE aggregate_id = :id")
                    .param("id", p.id()).query(Integer.class).single()).isPositive();
        } finally {
            docker.unpauseContainerCmd(containerId).exec();
        }

        // Broker back: everything is delivered, in order, for this payment.
        drain();
        List<JsonNode> events = dedupedEventsFor(p.uetr());
        assertThat(events).extracting(e -> e.get("to").asText())
                .containsExactly("RECEIVED", "VALIDATED", "SCREENED", "FUNDS_RESERVED", "SENT");
    }

    @Test
    void relay_crash_after_kafka_ack_redelivers_so_consumers_must_deduplicate() throws Exception {
        drain();
        PaymentView p = hub.create(payment(customer("EUR", "1000.00"), "100.00", ChargeBearer.SHAR)).value();

        // Kafka acknowledged the batch, then the relay "died" before marking the rows published.
        OutboxRelayTestAccess.crashAfterSend(relay);
        assertThatThrownBy(relay::publishBatch).hasMessageContaining("Simulated relay crash");
        OutboxRelayTestAccess.noCrash(relay);
        drain(); // after "restart" the same rows are sent again

        List<ConsumerRecord<String, String>> raw = recordsFor(p.uetr());
        List<String> eventIds = raw.stream().map(r -> header(r, "event-id")).toList();
        assertThat(raw).hasSizeGreaterThan(5);                        // duplicates on the wire ...
        assertThat(eventIds.stream().distinct().count()).isEqualTo(5); // ... but only 5 distinct events
        assertThat(dedupedEventsFor(p.uetr())).extracting(e -> e.get("to").asText())
                .containsExactly("RECEIVED", "VALIDATED", "SCREENED", "FUNDS_RESERVED", "SENT");
    }

    // ------------------------------------------------------------------ helpers

    private void drain() {
        while (relay.publishBatch() > 0) {
            // publish the backlog
        }
    }

    /** What a well-behaved consumer does: skip event ids it has already processed. */
    private List<JsonNode> dedupedEventsFor(String uetr) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ConsumerRecord<String, String> r : recordsFor(uetr)) {
            if (seen.add(header(r, "event-id"))) {
                out.add(json.readTree(r.value()));
            }
        }
        return out;
    }

    private List<ConsumerRecord<String, String>> recordsFor(String uetr) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(KafkaTopicsConfiguration.paymentEventsTopic("MNPAMYKLXXX")));
            long deadline = System.currentTimeMillis() + 10_000;
            int idlePolls = 0;
            while (System.currentTimeMillis() < deadline && idlePolls < 3) {
                var batch = consumer.poll(Duration.ofMillis(1000));
                boolean any = false;
                for (ConsumerRecord<String, String> r : batch) {
                    if (uetr.equals(r.key())) {
                        found.add(r);
                        any = true;
                    }
                }
                idlePolls = found.isEmpty() || any ? 0 : idlePolls + 1;
            }
        }
        return found;
    }

    private static String header(ConsumerRecord<String, String> r, String name) {
        return new String(r.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
