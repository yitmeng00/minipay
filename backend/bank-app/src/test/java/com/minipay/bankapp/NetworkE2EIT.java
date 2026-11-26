package com.minipay.bankapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.minipay.common.iso.ChargeBearer;
import com.minipay.common.money.Money;
import com.minipay.ledger.api.LedgerApi;
import com.minipay.network.api.Hop;
import com.minipay.network.api.TrackerApi;
import com.minipay.network.api.TrackerView;
import com.minipay.paymenthub.api.CreatePaymentCommand;
import com.minipay.paymenthub.api.PaymentHubApi;
import com.minipay.paymenthub.api.PaymentStatus;
import com.minipay.paymenthub.api.PaymentView;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The whole simulator in one JVM: Bank A, Bank B and Bank C — three complete applications, each with its
 * own PostgreSQL — talking to each other over one Kafka, exactly as they do when run from Compose.
 *
 * <p>Nothing here calls a bank on another bank's behalf: a payment is created at Bank A and everything
 * after that (forwarding, crediting, confirmations, cover, returns) happens through the network.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NetworkE2EIT {

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.0.0");
    static final Map<String, PostgreSQLContainer<?>> DB = Map.of(
            "bank-a", new PostgreSQLContainer<>("postgres:16-alpine"),
            "bank-b", new PostgreSQLContainer<>("postgres:16-alpine"),
            "bank-c", new PostgreSQLContainer<>("postgres:16-alpine"));
    /** Tuesday 24 Nov 2026, 10:00 in Kuala Lumpur: a business day before every cut-off. */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T02:00:00Z"), ZoneOffset.UTC);

    static ConfigurableApplicationContext a, b, c;
    static String serialUetr;

    @BeforeAll
    static void startTheNetwork() {
        KAFKA.start();
        DB.values().parallelStream().forEach(PostgreSQLContainer::start);
        a = bank("bank-a");
        b = bank("bank-b");
        c = bank("bank-c");
    }

    @AfterAll
    static void stop() {
        for (ConfigurableApplicationContext ctx : new ConfigurableApplicationContext[] {a, b, c}) {
            if (ctx != null) {
                ctx.close();
            }
        }
    }

    static ConfigurableApplicationContext bank(String profile) {
        PostgreSQLContainer<?> db = DB.get(profile);
        return new SpringApplicationBuilder(BankApplication.class)
                .profiles(profile)
                .initializers(ctx -> ctx.getBeanFactory().registerSingleton("clock", CLOCK))
                // As command-line arguments, so they outrank the profile's own datasource and port.
                .run("--server.port=0",
                        "--spring.datasource.url=" + db.getJdbcUrl(),
                        "--spring.datasource.username=" + db.getUsername(),
                        "--spring.datasource.password=" + db.getPassword(),
                        "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                        "--minipay.outbox.relay.interval=PT0.2S");
    }

    // ------------------------------------------------------------------ the routes

    @Test
    @Order(1)
    void serial_a_to_c_through_b_every_bank_takes_its_charge_and_the_confirmation_comes_back() {
        Money aNostroAtB = booked(a, "1101-EUR-MNPB");
        Money bVostroOfA = booked(b, "DE87500100600778812003");
        Money bVostroOfC = booked(b, "DE65500100600778812011");
        Money cNostroAtB = booked(c, "1103-EUR-MNPB");
        Money creditor = booked(c, "GB23MNPC40051288779901");

        PaymentView p = pay("MNPCGB2LXXX", "GB23MNPC40051288779901", "1000.00", "EUR");
        serialUetr = p.uetr();
        assertThat(p.status()).isEqualTo(PaymentStatus.SENT);
        assertThat(p.instructedAgentBic()).isEqualTo("MNPBDEFFXXX");

        awaitStatus(a, p.uetr(), PaymentStatus.SETTLED);   // ACSP from B, then C's ACSC relayed by B
        assertThat(status(b, p.uetr())).isEqualTo(PaymentStatus.SETTLED);
        assertThat(status(c, p.uetr())).isEqualTo(PaymentStatus.SETTLED);

        // Bank B took EUR 15, Bank C EUR 10: the creditor gets 975.
        assertThat(booked(c, "GB23MNPC40051288779901")).isEqualTo(creditor.plus(eur("975.00")));
        // Every nostro mirrors the vostro at the other bank: what reconciliation (M8) will check.
        assertThat(aNostroAtB.minus(booked(a, "1101-EUR-MNPB"))).isEqualTo(eur("1000.00"));
        assertThat(bVostroOfA.minus(booked(b, "DE87500100600778812003"))).isEqualTo(eur("1000.00"));
        assertThat(booked(b, "DE65500100600778812011").minus(bVostroOfC)).isEqualTo(eur("985.00"));
        assertThat(booked(c, "1103-EUR-MNPB").minus(cNostroAtB)).isEqualTo(eur("985.00"));

        TrackerView t = track(a, p.uetr(), "ACSC");
        assertThat(t.method()).isEqualTo("SERIAL");
        assertThat(t.hops()).extracting(Hop::fromBic, Hop::toBic).containsExactly(
                org.assertj.core.groups.Tuple.tuple("MNPAMYKLXXX", "MNPBDEFFXXX"),
                org.assertj.core.groups.Tuple.tuple("MNPBDEFFXXX", "MNPCGB2LXXX"));
        assertThat(t.creditedAmount()).isEqualTo(eur("975.00"));
        assertThat(t.deductions()).extracting(d -> d.agentBic()).containsExactly("MNPBDEFFXXX", "MNPCGB2LXXX");
        integrity();
    }

    @Test
    @Order(2)
    void cover_a_sends_the_payment_to_c_and_the_money_through_b() {
        Money creditor = booked(c, "GB23MNPC40051288779901");
        PaymentView p = pay("MNPCGB2L200", "GB23MNPC40051288779901", "2000.00", "EUR");
        assertThat(p.settlementMethod()).isEqualTo("COVE");
        assertThat(p.instructedAgentBic()).isEqualTo("MNPCGB2L200"); // the pacs.008 goes straight to Bank C

        awaitStatus(a, p.uetr(), PaymentStatus.SETTLED);
        assertThat(status(b, p.uetr())).isEqualTo(PaymentStatus.SETTLED); // Bank B settled the cover leg
        assertThat(booked(c, "GB23MNPC40051288779901")).isEqualTo(creditor.plus(eur("1990.00"))); // only Bank C's 10

        TrackerView t = track(a, p.uetr(), "ACSC");
        assertThat(t.method()).isEqualTo("COVER");
        assertThat(t.hops()).hasSize(1);
        assertThat(t.coverLegs()).extracting(Hop::type).containsExactly("pacs.009.001.08", "camt.054.001.08");
        integrity();
    }

    @Test
    @Order(3)
    void direct_gbp_a_to_c_settles_on_as_vostro_at_c() {
        Money creditor = booked(c, "GB67MNPC40051271520093");
        PaymentView p = pay("MNPCGB2LXXX", "GB67MNPC40051271520093", "500.00", "GBP");
        awaitStatus(a, p.uetr(), PaymentStatus.SETTLED);
        assertThat(booked(c, "GB67MNPC40051271520093")).isEqualTo(creditor.plus(Money.of("495.00", "GBP")));
        assertThat(track(a, p.uetr(), "ACSC").method()).isEqualTo("DIRECT");
    }

    @Test
    @Order(4)
    void a_return_by_c_travels_back_through_b_to_a() {
        PaymentHubApi hubC = c.getBean(PaymentHubApi.class);
        Money debtorBefore = booked(a, "514087234412");

        hubC.returnPayment("e2e-return-1", serialUetr, "AC04", "Account closed");

        awaitStatus(a, serialUetr, PaymentStatus.RETURNED);
        assertThat(status(b, serialUetr)).isEqualTo(PaymentStatus.RETURNED);
        // C returns what it credited (975); nobody deducts more on the way back.
        assertThat(booked(a, "514087234412")).isEqualTo(debtorBefore.plus(eur("975.00")));
        TrackerView t = track(a, serialUetr, "RTND");
        assertThat(t.returnPath()).extracting(Hop::fromBic).containsExactly("MNPCGB2LXXX", "MNPBDEFFXXX");
        integrity();
    }

    @Test
    @Order(5)
    void a_rejection_by_c_is_reversed_at_b_and_at_a() {
        Money debtorBefore = booked(a, "514087234412");
        Money bVostroOfA = booked(b, "DE87500100600778812003");
        // A valid IBAN at Bank C, but no such account there.
        PaymentView p = pay("MNPCGB2LXXX", "GB12MNPC40051299990001", "300.00", "EUR");

        awaitStatus(a, p.uetr(), PaymentStatus.REJECTED);
        assertThat(status(b, p.uetr())).isEqualTo(PaymentStatus.REJECTED);
        assertThat(a.getBean(PaymentHubApi.class).get(p.uetr()).payment().rejectionReason()).isEqualTo("AC01");
        assertThat(booked(a, "514087234412")).isEqualTo(debtorBefore);           // principal and fee back
        assertThat(booked(b, "DE87500100600778812003")).isEqualTo(bVostroOfA);   // B undid its booking too
        integrity();
    }

    // ------------------------------------------------------------------ helpers

    private PaymentView pay(String creditorAgent, String creditorAccount, String amount, String ccy) {
        String debtor = ccy.equals("GBP") ? "514055218840" : "514087234412";
        return a.getBean(PaymentHubApi.class).create(new CreatePaymentCommand("e2e-" + UUID.randomUUID(), debtor,
                "Harlow & Finch Engineering Ltd", creditorAccount, creditorAgent, Money.of(amount, ccy), ChargeBearer.SHAR,
                null, "E2E-" + UUID.randomUUID().toString().substring(0, 8), "End-to-end test")).value();
    }

    private static void awaitStatus(ConfigurableApplicationContext bank, String uetr, PaymentStatus expected) {
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertThat(status(bank, uetr)).isEqualTo(expected));
    }

    private static PaymentStatus status(ConfigurableApplicationContext bank, String uetr) {
        try {
            return bank.getBean(PaymentHubApi.class).get(uetr).payment().status();
        } catch (RuntimeException e) {
            return null; // not arrived yet
        }
    }

    private static TrackerView track(ConfigurableApplicationContext bank, String uetr, String status) {
        TrackerApi tracker = bank.getBean(TrackerApi.class);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertThat(tracker.track(uetr)).get().extracting(TrackerView::status).isEqualTo(status));
        return tracker.track(uetr).orElseThrow();
    }

    private static Money booked(ConfigurableApplicationContext bank, String account) {
        return bank.getBean(LedgerApi.class).findAccountByNumber(account).orElseThrow().booked();
    }

    private static void integrity() {
        for (ConfigurableApplicationContext bank : new ConfigurableApplicationContext[] {a, b, c}) {
            assertThat(bank.getBean(LedgerApi.class).checkIntegrity().ok()).isTrue();
        }
    }

    private static Money eur(String amount) {
        return Money.of(amount, "EUR");
    }
}
