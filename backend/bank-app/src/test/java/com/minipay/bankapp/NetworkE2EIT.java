package com.minipay.bankapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.minipay.common.iso.ChargeBearer;
import com.minipay.common.money.Money;
import com.minipay.compliance.api.CaseStatus;
import com.minipay.compliance.api.CaseView;
import com.minipay.compliance.api.ComplianceApi;
import com.minipay.compliance.api.DecideCommand;
import com.minipay.compliance.api.DecisionAction;
import com.minipay.compliance.api.DecisionReason;
import com.minipay.ledger.api.LedgerApi;
import com.minipay.network.api.Hop;
import com.minipay.network.api.TrackerApi;
import com.minipay.network.api.TrackerView;
import com.minipay.paymenthub.api.CreatePaymentCommand;
import com.minipay.paymenthub.api.PaymentHubApi;
import com.minipay.paymenthub.api.PaymentStatus;
import com.minipay.paymenthub.api.PaymentView;
import com.minipay.paymenthub.api.ServiceLevel;
import com.minipay.settlement.api.SettlementOperatorApi;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 * after that (forwarding, crediting, confirmations, cover, returns) happens through the network. The
 * compliance officers' decisions are the only other input, each at its own bank.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NetworkE2EIT {

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.0.0");
    static final Map<String, PostgreSQLContainer<?>> DB = Map.of(
            "bank-a", new PostgreSQLContainer<>("postgres:16-alpine"),
            "bank-b", new PostgreSQLContainer<>("postgres:16-alpine"),
            "bank-c", new PostgreSQLContainer<>("postgres:16-alpine"),
            "operator", new PostgreSQLContainer<>("postgres:16-alpine"));
    /**
     * Thursday 26 Nov 2026, 10:00 in Kuala Lumpur (03:00 in Frankfurt): a business day before every cut-off.
     * The clock ticks from there: the instant scheme's 10-second timeout must be able to pass.
     */
    static final Clock CLOCK = Clock.offset(Clock.systemUTC(),
            Duration.between(Instant.now(), Instant.parse("2026-11-26T02:00:00Z")));

    static ConfigurableApplicationContext a, b, c, operator;
    static String serialUetr;

    @BeforeAll
    static void startTheNetwork() {
        KAFKA.start();
        DB.values().parallelStream().forEach(PostgreSQLContainer::start);
        operator = bank("operator"); // the central bank: T2, MiniPay Net, MiniPay Instant (ADR-008)
        a = bank("bank-a");
        b = bank("bank-b");
        c = bank("bank-c");
    }

    @AfterAll
    static void stop() {
        for (ConfigurableApplicationContext ctx : new ConfigurableApplicationContext[] {a, b, c, operator}) {
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
                        "--minipay.outbox.relay.interval=PT0.2S",
                        "--minipay.settlement.simulated-answer-delay=PT0.3S");
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

    // ------------------------------------------------------------------ sanctions screening (M6)

    static final String OAKRIDGE = "GB20MNPC40051290550011";
    static final String FALSE_POSITIVE = "Different entity: UK company, Companies House 09811234; the listed party is a UAE free-zone company";

    /**
     * Every bank screens for itself. The creditor's name resembles a listed entity, so the payment is held
     * at Bank A, and once Bank A releases it, again at Bank B, and again at Bank C. Each bank's release
     * needs two officers (score ≥ 90). Only then is the creditor credited and the ACSC sent back.
     */
    @Test
    @Order(6)
    void every_bank_on_the_route_screens_the_payment_and_releases_it_with_four_eyes() {
        Money creditor = booked(c, OAKRIDGE);
        PaymentView p = pay("MNPCGB2LXXX", OAKRIDGE, "1000.00", "EUR", "Oakridge Interiors Ltd");
        assertThat(p.status()).isEqualTo(PaymentStatus.HELD_COMPLIANCE);

        release(a, p.uetr());
        awaitStatus(b, p.uetr(), PaymentStatus.HELD_COMPLIANCE);
        assertThat(status(a, p.uetr())).isEqualTo(PaymentStatus.SENT);
        release(b, p.uetr());
        awaitStatus(c, p.uetr(), PaymentStatus.HELD_COMPLIANCE);
        assertThat(booked(c, OAKRIDGE)).isEqualTo(creditor); // nothing credited while held
        release(c, p.uetr());

        awaitStatus(a, p.uetr(), PaymentStatus.SETTLED);
        assertThat(booked(c, OAKRIDGE)).isEqualTo(creditor.plus(eur("975.00")));
        for (ConfigurableApplicationContext bank : new ConfigurableApplicationContext[] {a, b, c}) {
            CaseView cv = caseAt(bank, p.uetr());
            assertThat(cv.status()).isEqualTo(CaseStatus.RELEASED);
            assertThat(cv.decisions()).extracting(d -> d.decidedBy()).containsExactly("n.rahman", "j.tan");
        }
        track(a, p.uetr(), "ACSC");
        integrity();
    }

    /**
     * Bank A released the payment, Bank B finds a true match and refuses it: pacs.002 RJCT RR04
     * ("regulatory reason"), with nothing about why. Bank A reverses its booking; the debtor has the
     * money and the fee back. Bank C never sees the payment.
     */
    @Test
    @Order(7)
    void a_true_match_at_the_intermediary_is_rejected_RR04_and_reversed_at_the_debtors_bank() {
        Money debtorBefore = booked(a, "514087234412");
        PaymentView p = pay("MNPCGB2LXXX", OAKRIDGE, "400.00", "EUR", "Oakridge Interiors Ltd");
        release(a, p.uetr());
        awaitStatus(b, p.uetr(), PaymentStatus.HELD_COMPLIANCE);

        decide(b, caseAt(b, p.uetr()).id(), DecisionAction.REJECT, DecisionReason.TRUE_MATCH,
                "Registry check: this is the listed company trading under its UK name", "k.weber");

        awaitStatus(a, p.uetr(), PaymentStatus.REJECTED);
        PaymentView atA = a.getBean(PaymentHubApi.class).get(p.uetr()).payment();
        assertThat(atA.rejectionReason()).isEqualTo("RR04");
        assertThat(booked(a, "514087234412")).isEqualTo(debtorBefore);
        assertThat(status(c, p.uetr())).isNull();
        integrity();
    }


    // ------------------------------------------------------------------ the clearing systems (M7, ADR-008)

    static final String C_EUR = "GB23MNPC40051277330021";      // Pennine Textiles Ltd (EUR), at Bank C
    static final String B_RHEINWERK = "DE34500100600533190042"; // Rheinwerk Logistik GmbH, at Bank B

    @Test
    @Order(8)
    void an_urgent_payment_settles_gross_in_t2_and_every_mirror_moves_with_the_central_banks_books() {
        Mirrors before = mirrors();
        Money creditor = booked(b, B_RHEINWERK);
        PaymentView p = payFrom(c, C_EUR, "MNPBDEFFXXX", B_RHEINWERK, "Rheinwerk Logistik GmbH", "5000.00", ServiceLevel.HIGH);
        assertThat(p.clearingSystem()).isEqualTo("T2");
        assertThat(p.viaBic()).isEqualTo("MNCBDEFFXXX");

        awaitStatus(c, p.uetr(), PaymentStatus.SETTLED); // T2's ACSC: final
        awaitStatus(b, p.uetr(), PaymentStatus.SETTLED);
        assertThat(booked(b, B_RHEINWERK)).isEqualTo(creditor.plus(eur("4985.00"))); // Bank B's charge 15 (SHAR)
        Mirrors after = mirrors();
        assertThat(before.cT2().minus(after.cT2())).isEqualTo(eur("5000.00"));
        assertThat(after.bT2().minus(before.bT2())).isEqualTo(eur("5000.00"));
        after.match();
        integrity();
    }

    @Test
    @Order(9)
    void normal_payments_wait_for_the_cycle_and_only_the_net_positions_settle() {
        Mirrors before = mirrors();
        PaymentView cToB = payFrom(c, C_EUR, "MNPBDEFFXXX", B_RHEINWERK, "Rheinwerk Logistik GmbH", "3000.00", ServiceLevel.NORM);
        PaymentView bToD = payFrom(b, "DE13500100600412280017", "MNPDBEBBXXX", "BE71096123456769", "De Witte Fietsen NV",
                "2000.00", ServiceLevel.NORM);
        assertThat(cToB.clearingSystem()).isEqualTo("MPNET");
        assertThat(bToD.clearingSystem()).isEqualTo("MPNET");
        awaitStatus(c, cToB.uetr(), PaymentStatus.ACKED); // accepted into the cycle (ACSP), nothing moved yet
        awaitStatus(b, bToD.uetr(), PaymentStatus.ACKED);
        assertThat(status(b, cToB.uetr())).isNull();      // the ACH delivers only after settlement

        var cycle = operator.getBean(SettlementOperatorApi.class).closeCycle("e2e-close-" + UUID.randomUUID()).value();
        assertThat(cycle.status()).isEqualTo("SETTLED");
        awaitStatus(c, cToB.uetr(), PaymentStatus.SETTLED);
        awaitStatus(b, bToD.uetr(), PaymentStatus.SETTLED);
        awaitStatus(b, cToB.uetr(), PaymentStatus.SETTLED);
        // Net: C pays 3000, B receives 3000 and pays 2000, D receives 2000. 5000 of payments, 3000 of liquidity.
        assertThat(cycle.grossTotal()).isEqualTo(eur("5000.00"));
        assertThat(cycle.multilateralTotal()).isEqualTo(eur("3000.00"));
        Mirrors after = mirrors();
        assertThat(after.bT2().minus(before.bT2())).isEqualTo(eur("1000.00"));
        after.match();
        integrity();
    }

    @Test
    @Order(10)
    void an_instant_payment_is_accepted_by_the_creditors_bank_and_settled_on_the_prefunded_positions() {
        Mirrors before = mirrors();
        Money creditor = booked(c, C_EUR);
        PaymentView p = payFrom(b, B_RHEINWERK, "MNPCGB2LXXX", C_EUR, "Pennine Textiles Ltd", "250.00", ServiceLevel.INST);
        assertThat(p.clearingSystem()).isEqualTo("MPINST");

        awaitStatus(b, p.uetr(), PaymentStatus.SETTLED);
        awaitStatus(c, p.uetr(), PaymentStatus.SETTLED);
        assertThat(booked(c, C_EUR)).isEqualTo(creditor.plus(eur("240.00"))); // Bank C's charge 10
        Mirrors after = mirrors();
        assertThat(before.bInst().minus(after.bInst())).isEqualTo(eur("250.00"));
        assertThat(after.cInst().minus(before.cInst())).isEqualTo(eur("250.00"));
        after.match();
        integrity();
    }

    @Test
    @Order(11)
    void an_instant_payment_held_for_compliance_at_the_creditors_bank_times_out_ab05_and_is_reversed() {
        Mirrors before = mirrors();
        Money debtor = booked(b, B_RHEINWERK);
        PaymentView p = payFrom(b, B_RHEINWERK, "MNPCGB2LXXX", OAKRIDGE, "Oakridge Interiors Ltd", "300.00", ServiceLevel.INST);
        // Bank B screens it first and holds it; released with four eyes, it goes out instantly.
        UUID caseId = caseAt(b, p.uetr()).id();
        decide(b, caseId, DecisionAction.RELEASE, DecisionReason.FALSE_POSITIVE, FALSE_POSITIVE, "k.weber");
        decide(b, caseId, DecisionAction.APPROVE, null, FALSE_POSITIVE, "m.vogel");
        awaitStatus(c, p.uetr(), PaymentStatus.HELD_COMPLIANCE); // Bank C holds it too, and cannot answer in 10 s

        awaitStatus(b, p.uetr(), PaymentStatus.REJECTED);
        awaitStatus(c, p.uetr(), PaymentStatus.REJECTED);
        assertThat(b.getBean(PaymentHubApi.class).get(p.uetr()).payment().rejectionReason()).isEqualTo("AB05");
        assertThat(booked(b, B_RHEINWERK)).isEqualTo(debtor);
        Mirrors after = mirrors();
        assertThat(after.bInst()).isEqualTo(before.bInst());
        after.match();
        integrity();
    }

    @Test
    @Order(12)
    void a_settled_t2_payment_the_creditors_bank_cannot_apply_goes_back_as_a_return_through_t2() {
        Mirrors before = mirrors();
        Money debtor = booked(c, C_EUR);
        PaymentView p = payFrom(c, C_EUR, "MNPBDEFFXXX", "DE19500100600999999999", "Closed Account GmbH", "700.00", ServiceLevel.HIGH);

        awaitStatus(b, p.uetr(), PaymentStatus.REJECTED);   // AC01 at Bank B, after T2 had settled it
        awaitStatus(c, p.uetr(), PaymentStatus.RETURNED);   // the pacs.004 settled back through T2
        assertThat(booked(c, C_EUR)).isEqualTo(debtor);
        Mirrors after = mirrors();
        assertThat(after.cT2()).isEqualTo(before.cT2());
        after.match();
        integrity();
    }

    /** Each bank's mirrors of its accounts at the central bank, and the central bank's own books. */
    record Mirrors(Money bT2, Money cT2, Money bInst, Money cInst, Money opBT2, Money opCT2, Money opBInst, Money opCInst) {
        /** What reconciliation (M8) will check: every mirror equals the central bank's account, to the cent. */
        void match() {
            assertThat(bT2).isEqualTo(opBT2);
            assertThat(cT2).isEqualTo(opCT2);
            assertThat(bInst).isEqualTo(opBInst);
            assertThat(cInst).isEqualTo(opCInst);
        }
    }

    private static Mirrors mirrors() {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250)).until(() -> {
            Mirrors m = readMirrors();
            return m.bT2().equals(m.opBT2()) && m.cT2().equals(m.opCT2()) && m.bInst().equals(m.opBInst())
                    && m.cInst().equals(m.opCInst());
        });
        return readMirrors();
    }

    private static Mirrors readMirrors() {
        return new Mirrors(booked(b, "1001-EUR-T2"), booked(c, "1004-EUR-T2"), booked(b, "1002-EUR-INST"),
                booked(c, "1005-EUR-INST"), booked(operator, "RTGS-MNPBDEFF"), booked(operator, "RTGS-MNPCGB2L"),
                booked(operator, "INST-MNPBDEFF"), booked(operator, "INST-MNPCGB2L"));
    }

    private static PaymentView payFrom(ConfigurableApplicationContext bank, String debtor, String creditorAgent,
                                       String creditorAccount, String creditorName, String amount, ServiceLevel level) {
        return bank.getBean(PaymentHubApi.class).create(new CreatePaymentCommand("e2e-" + UUID.randomUUID(), debtor,
                creditorName, creditorAccount, creditorAgent, Money.of(amount, "EUR"), ChargeBearer.SHAR, null,
                "E2E-" + UUID.randomUUID().toString().substring(0, 8), "End-to-end test", level)).value();
    }

    private static void release(ConfigurableApplicationContext bank, String uetr) {
        UUID caseId = caseAt(bank, uetr).id();
        decide(bank, caseId, DecisionAction.RELEASE, DecisionReason.FALSE_POSITIVE, FALSE_POSITIVE, "n.rahman");
        decide(bank, caseId, DecisionAction.APPROVE, null, FALSE_POSITIVE, "j.tan");
    }

    private static void decide(ConfigurableApplicationContext bank, UUID caseId, DecisionAction action, DecisionReason reason,
                               String comment, String by) {
        bank.getBean(ComplianceApi.class).decide(new DecideCommand("e2e-" + UUID.randomUUID(), caseId, action, reason, comment, by));
    }

    private static CaseView caseAt(ConfigurableApplicationContext bank, String uetr) {
        UUID paymentId = bank.getBean(PaymentHubApi.class).get(uetr).payment().id();
        return bank.getBean(ComplianceApi.class).caseForPayment(paymentId).orElseThrow();
    }

    // ------------------------------------------------------------------ helpers

    private PaymentView pay(String creditorAgent, String creditorAccount, String amount, String ccy) {
        return pay(creditorAgent, creditorAccount, amount, ccy, "Harlow & Finch Engineering Ltd");
    }

    private PaymentView pay(String creditorAgent, String creditorAccount, String amount, String ccy, String creditorName) {
        String debtor = ccy.equals("GBP") ? "514055218840" : "514087234412";
        return a.getBean(PaymentHubApi.class).create(new CreatePaymentCommand("e2e-" + UUID.randomUUID(), debtor,
                creditorName, creditorAccount, creditorAgent, Money.of(amount, ccy), ChargeBearer.SHAR,
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
        for (ConfigurableApplicationContext bank : new ConfigurableApplicationContext[] {a, b, c, operator}) {
            assertThat(bank.getBean(LedgerApi.class).checkIntegrity().ok()).isTrue();
        }
    }

    private static Money eur(String amount) {
        return Money.of(amount, "EUR");
    }
}
