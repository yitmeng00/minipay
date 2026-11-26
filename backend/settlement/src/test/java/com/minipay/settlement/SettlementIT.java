package com.minipay.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minipay.common.id.Uetr;
import com.minipay.common.iso.ChargeBearer;
import com.minipay.common.money.Money;
import com.minipay.ledger.api.AccountType;
import com.minipay.ledger.api.Direction;
import com.minipay.ledger.api.EntryType;
import com.minipay.ledger.api.LedgerApi;
import com.minipay.ledger.api.OpenAccountCommand;
import com.minipay.ledger.api.PostEntryCommand;
import com.minipay.ledger.api.PostingLine;
import com.minipay.messaging.api.Clearing;
import com.minipay.messaging.api.CreditTransfer;
import com.minipay.messaging.api.MessageType;
import com.minipay.messaging.api.MessageView;
import com.minipay.messaging.api.MessagingApi;
import com.minipay.messaging.api.PaymentReturn;
import com.minipay.messaging.api.SettlementMethod;
import com.minipay.messaging.api.StatusReport;
import com.minipay.messaging.api.TransactionStatus;
import com.minipay.settlement.SettlementTestApplication.Network;
import com.minipay.settlement.SettlementTestApplication.TestClock;
import com.minipay.settlement.api.SettlementError;
import com.minipay.settlement.api.SettlementException;
import com.minipay.settlement.api.SettlementOperatorApi;
import com.minipay.settlement.api.TransferStatus;
import com.minipay.settlement.api.TransferView;
import com.minipay.settlement.api.Views;
import com.minipay.settlement.internal.OperatorService;
import com.minipay.settlement.internal.config.SettlementProperties;
import com.minipay.settlement.internal.persistence.SettlementRepository;
import com.minipay.settlement.internal.persistence.SettlementRepository.ParticipantRow;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The three systems against a real PostgreSQL, driven through real messages. The ledger is append-only, so
 * every test opens its own participants (fresh BICs and accounts) and asserts only on them.
 */
@SpringBootTest(classes = SettlementTestApplication.class)
class SettlementIT {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 26);
    static final String CB = "MNCBDEFFXXX";
    static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired SettlementOperatorApi api;
    @Autowired OperatorService operator;
    @Autowired SettlementRepository repo;
    @Autowired LedgerApi ledger;
    @Autowired MessagingApi messaging;
    @Autowired Network network;
    @Autowired TestClock clock;

    @BeforeEach
    void reset() {
        clock.set(SettlementTestApplication.NOW);
    }

    // ------------------------------------------------------------------ RTGS

    @Test
    void a_covered_rtgs_payment_settles_at_once_is_final_and_goes_on_to_the_payee_unchanged() {
        String a = bank("1000.00", "0"), b = bank("0", "0");
        Views.Receipt r = api.receive(pacs008(a, b, "400.00", "T2", "NORM", false));

        assertThat(r.status()).isEqualTo(TransferStatus.SETTLED);
        assertThat(rtgs(a)).isEqualByComparingTo("600.00");
        assertThat(rtgs(b)).isEqualByComparingTo("400.00");
        TransferView t = api.transfer(r.transferId()).transfer();
        MessageView forwarded = network.to(b).get(0);
        assertThat(forwarded.type()).isEqualTo(MessageType.PACS_008);
        assertThat(forwarded.senderBic()).isEqualTo(CB);
        assertThat(forwarded.msgId()).isEqualTo(t.msgId()); // the payer's message, re-addressed
        String original = messaging.get(t.messageId()).raw();
        String relayed = messaging.get(forwarded.id()).raw();
        assertThat(relayed.substring(relayed.indexOf("Document"))).isEqualTo(original.substring(original.indexOf("Document")));
        assertThat(network.to(a)).extracting(MessageView::status).containsExactly("ACSC");
    }

    @Test
    void an_uncovered_payment_queues_and_settles_when_liquidity_arrives() {
        String a = bank("100.00", "0"), b = bank("100.00", "0");
        Views.Receipt queued = api.receive(pacs008(a, b, "150.00", "T2", "NORM", false));
        assertThat(queued.status()).isEqualTo(TransferStatus.QUEUED);
        Views.Rtgs view = api.rtgs(a);
        assertThat(view.queue()).singleElement().satisfies(q -> assertThat(q.waiting()).startsWith("Insufficient liquidity"));
        assertThat(api.rtgs(b).queuedIn()).isEqualTo(Money.of("150.00", "EUR"));
        assertThat(network.to(a)).isEmpty(); // nothing to tell yet: queued is not settled

        // B pays A 80: A now has 180, and its queued payment settles in the same flow.
        api.receive(pacs008(b, a, "80.00", "T2", "NORM", false));
        assertThat(api.transfer(queued.transferId()).transfer().status()).isEqualTo(TransferStatus.SETTLED);
        assertThat(rtgs(a)).isEqualByComparingTo("30.00");
        assertThat(rtgs(b)).isEqualByComparingTo("170.00");
        assertThat(api.transfer(queued.transferId()).events()).extracting(Views.TransferEvent::detail)
                .anySatisfy(d -> assertThat(d).startsWith("Settled from the queue"));
    }

    @Test
    void the_payer_can_make_a_queued_payment_urgent_and_the_command_is_idempotent() {
        String a = bank("100.00", "0"), b = bank("0", "0");
        UUID first = api.receive(pacs008(a, b, "70.00", "T2", "NORM", false)).transferId();
        UUID second = api.receive(pacs008(a, b, "60.00", "T2", "NORM", false)).transferId();
        assertThat(api.transfer(first).transfer().status()).isEqualTo(TransferStatus.SETTLED); // 70 fit, 30 left
        assertThat(api.transfer(second).transfer().status()).isEqualTo(TransferStatus.QUEUED);

        String key = "prio-" + second;
        var made = api.changePriority(key, second, "HIGH", a);
        assertThat(made.replayed()).isFalse();
        assertThat(made.value().priority()).isEqualTo("HIGH");
        assertThat(api.changePriority(key, second, "HIGH", a).replayed()).isTrue();
        assertThatThrownBy(() -> api.changePriority(key, second, "NORM", a))
                .isInstanceOf(SettlementException.class).extracting("error").isEqualTo(SettlementError.IDEMPOTENCY_CONFLICT);
        assertThatThrownBy(() -> api.changePriority("other-" + second, second, "NORM", b))
                .isInstanceOf(SettlementException.class).hasMessageContaining("Only the payer");
    }

    @Test
    void what_is_still_queued_at_the_cutoff_is_rejected_and_nothing_carries_into_the_next_day() {
        String a = bank("10.00", "0"), b = bank("0", "0");
        UUID id = api.receive(pacs008(a, b, "50.00", "T2", "HIGH", false)).transferId();
        assertThat(operator.endOfDay()).isZero(); // 10:00: long before the cut-off

        clock.set(Instant.parse("2026-11-26T16:00:00Z")); // 17:00 in Frankfurt
        assertThat(operator.endOfDay()).isGreaterThanOrEqualTo(1);
        TransferView t = api.transfer(id).transfer();
        assertThat(t.status()).isEqualTo(TransferStatus.REJECTED);
        assertThat(t.reasonCode()).isEqualTo("AM04");
        assertThat(network.to(a)).singleElement().satisfies(m -> {
            assertThat(m.status()).isEqualTo("RJCT");
            assertThat(m.reasonCode()).isEqualTo("AM04");
        });
        assertThat(rtgs(a)).isEqualByComparingTo("10.00");
        // And after the cut-off a payment for today is refused on arrival (TM01).
        assertThat(api.receive(pacs008(a, b, "1.00", "T2", "NORM", false)).detail()).startsWith("TM01");
    }

    /**
     * Two payments from one payer arrive together; each fits alone, both do not. The payer's lock makes the
     * second see what the first left: one settles, one queues, and the account never goes below zero. Without
     * the lock (mutation: remove lockParticipants in RtgsEngine.process) both see 100, both try to settle, and
     * the loser's whole arrival fails on the ledger's funds check.
     */
    @Test
    void racing_payments_from_one_payer_never_overdraw_it() throws Exception {
        String a = bank("100.00", "0"), b = bank("0", "0");
        List<String> raws = List.of(pacs008(a, b, "60.00", "T2", "NORM", false), pacs008(a, b, "60.00", "T2", "NORM", false));
        List<Views.Receipt> receipts = race(raws.stream().<Callable<Views.Receipt>>map(r -> () -> api.receive(r)).toList());
        assertThat(receipts).extracting(Views.Receipt::status)
                .containsExactlyInAnyOrder(TransferStatus.SETTLED, TransferStatus.QUEUED);
        assertThat(rtgs(a)).isEqualByComparingTo("40.00");
    }

    @Test
    void a_payee_that_is_not_a_participant_is_refused_cnor_and_a_redelivery_is_recognised() {
        String a = bank("100.00", "0");
        String raw = pacs008(a, "MNPXNL2AXXX", "10.00", "T2", "NORM", false);
        Views.Receipt r = api.receive(raw);
        assertThat(r.status()).isEqualTo(TransferStatus.REJECTED);
        assertThat(r.detail()).startsWith("CNOR");
        assertThat(network.to(a)).singleElement().satisfies(m -> assertThat(m.reasonCode()).isEqualTo("CNOR"));
        Views.Receipt again = api.receive(raw);
        assertThat(again.replayed()).isTrue();
        assertThat(again.transferId()).isEqualTo(r.transferId());
    }

    @Test
    void a_return_settles_back_through_the_rtgs_to_the_original_payer() {
        String a = bank("500.00", "0"), b = bank("0", "0");
        UUID id = api.receive(pacs008(a, b, "200.00", "T2", "NORM", false)).transferId();
        TransferView paid = api.transfer(id).transfer();
        String pacs004 = messaging.buildPacs004(new PaymentReturn(b, CB, "RTN-" + paid.instructionId(), paid.msgId(),
                paid.instructionId(), paid.endToEndId(), paid.uetr().toString(), paid.amount(), paid.amount(), TODAY,
                List.of(), "AC01", null));
        Views.Receipt r = api.receive(pacs004);
        assertThat(r.status()).isEqualTo(TransferStatus.SETTLED);
        assertThat(rtgs(a)).isEqualByComparingTo("500.00");
        assertThat(rtgs(b)).isEqualByComparingTo("0.00");
        assertThat(network.to(a)).extracting(MessageView::type).contains(MessageType.PACS_004);
    }

    // ------------------------------------------------------------------ DNS

    @Test
    void a_dns_cycle_settles_only_the_net_positions_and_delivers_the_payments_after_settlement() {
        String a = bank("50.00", "0"), b = bank("0", "0"), c = bank("0", "0");
        UUID ab = api.receive(pacs008(a, b, "100.00", "MPNET", null, false)).transferId();
        UUID ba = api.receive(pacs008(b, a, "70.00", "MPNET", null, false)).transferId();
        UUID bc = api.receive(pacs008(b, c, "30.00", "MPNET", null, false)).transferId();
        TransferView t = api.transfer(ab).transfer();
        assertThat(t.status()).isEqualTo(TransferStatus.ACCEPTED);
        assertThat(t.cycleId()).startsWith("MPNET-20261126-");
        assertThat(network.to(a)).extracting(MessageView::status).containsExactly("ACSP");
        assertThat(network.to(b)).extracting(MessageView::type).doesNotContain(MessageType.PACS_008); // not before settlement
        assertThat(rtgs(a)).isEqualByComparingTo("50.00");

        Views.DnsCycle open = api.cycle(t.cycleId());
        assertThat(open.status()).isEqualTo("OPEN");
        assertThat(open.grossTotal()).isEqualTo(Money.of("200.00", "EUR"));
        assertThat(open.multilateralTotal()).isEqualTo(Money.of("30.00", "EUR"));

        clock.set(open.scheduledClose());
        assertThat(operator.closeDueCycles()).isGreaterThanOrEqualTo(1);
        Views.DnsCycle settled = api.cycle(t.cycleId());
        assertThat(settled.status()).isEqualTo("SETTLED");
        assertThat(settled.unwound()).isEmpty();
        // A pays 30 net, B nets to zero, C receives 30: one booking of 30, for 200 of payments.
        assertThat(rtgs(a)).isEqualByComparingTo("20.00");
        assertThat(rtgs(b)).isEqualByComparingTo("0.00");
        assertThat(rtgs(c)).isEqualByComparingTo("30.00");
        for (UUID id : List.of(ab, ba, bc)) {
            assertThat(api.transfer(id).transfer().status()).isEqualTo(TransferStatus.SETTLED);
        }
        assertThat(network.to(b)).extracting(MessageView::type).contains(MessageType.PACS_008);
        assertThat(network.to(a)).extracting(MessageView::status).contains("ACSC");
    }

    @Test
    void a_net_payer_that_cannot_pay_is_unwound_and_its_payments_are_rejected() {
        String a = bank("10.00", "0"), b = bank("0", "0"), c = bank("100.00", "0");
        UUID ab = api.receive(pacs008(a, b, "100.00", "MPNET", null, false)).transferId();
        UUID cb = api.receive(pacs008(c, b, "40.00", "MPNET", null, false)).transferId();
        String cycleId = api.transfer(ab).transfer().cycleId();

        var closed = api.closeCycle("close-" + cycleId);
        assertThat(closed.value().unwound()).contains(a);
        assertThat(api.transfer(ab).transfer()).satisfies(t -> {
            assertThat(t.status()).isEqualTo(TransferStatus.REJECTED);
            assertThat(t.reasonCode()).isEqualTo("AM04");
            assertThat(t.reasonDetail()).contains("Unwound").contains(a);
        });
        assertThat(api.transfer(cb).transfer().status()).isEqualTo(TransferStatus.SETTLED);
        assertThat(rtgs(a)).isEqualByComparingTo("10.00");
        assertThat(rtgs(b)).isEqualByComparingTo("40.00");
        assertThat(api.closeCycle("close-" + cycleId).replayed()).isTrue();
    }

    // ------------------------------------------------------------------ instant

    @Test
    void an_instant_payment_reserves_the_position_and_settles_when_the_payee_accepts_in_time() {
        String a = bank("0", "500.00"), b = bank("0", "0");
        Views.Receipt r = api.receive(pacs008(a, b, "120.00", "MPINST", null, true));
        assertThat(r.status()).isEqualTo(TransferStatus.PENDING);
        assertThat(instant(a).available()).isEqualTo(Money.of("380.00", "EUR"));
        assertThat(instant(a).reserved()).isEqualTo(Money.of("120.00", "EUR"));
        assertThat(network.to(b)).extracting(MessageView::type).containsExactly(MessageType.PACS_008);

        clock.advance(Duration.ofMillis(1300));
        TransferView t = api.transfer(r.transferId()).transfer();
        Views.Receipt accepted = api.receive(answer(b, t, TransactionStatus.ACCP));
        assertThat(accepted.status()).isEqualTo(TransferStatus.SETTLED);
        assertThat(instant(a).available()).isEqualTo(Money.of("380.00", "EUR"));
        assertThat(instant(a).reserved()).isEqualTo(Money.of("0.00", "EUR"));
        assertThat(instant(b).available()).isEqualTo(Money.of("120.00", "EUR"));
        assertThat(network.to(a)).extracting(MessageView::status).containsExactly("ACSC");
        assertThat(network.to(b)).extracting(MessageView::status).contains("ACSC");
        assertThat(instant(a).medianMillis()).isEqualTo(1300L);
    }

    @Test
    void no_answer_within_the_timeout_rejects_ab05_releases_the_reservation_and_a_late_accept_changes_nothing() {
        String a = bank("0", "500.00"), b = bank("0", "0");
        UUID id = api.receive(pacs008(a, b, "120.00", "MPINST", null, true)).transferId();
        clock.advance(Duration.ofSeconds(9));
        operator.instantTimers();
        assertThat(api.transfer(id).transfer().status()).isEqualTo(TransferStatus.PENDING);

        clock.advance(Duration.ofSeconds(2)); // 11 s: past the 10 s timeout
        operator.instantTimers();
        TransferView t = api.transfer(id).transfer();
        assertThat(t.status()).isEqualTo(TransferStatus.REJECTED);
        assertThat(t.reasonCode()).isEqualTo("AB05");
        assertThat(instant(a).available()).isEqualTo(Money.of("500.00", "EUR"));
        assertThat(network.to(a)).singleElement().satisfies(m -> assertThat(m.reasonCode()).isEqualTo("AB05"));
        assertThat(network.to(b)).extracting(MessageView::reasonCode).contains("AB05"); // the payee is told too

        api.receive(answer(b, t, TransactionStatus.ACCP));
        assertThat(api.transfer(id).transfer().status()).isEqualTo(TransferStatus.REJECTED);
        assertThat(instant(b).available()).isEqualTo(Money.of("0.00", "EUR"));
    }

    @Test
    void an_instant_payment_beyond_the_prefunded_position_is_rejected_at_once() {
        String a = bank("1000000.00", "50.00"), b = bank("0", "0");
        Views.Receipt r = api.receive(pacs008(a, b, "60.00", "MPINST", null, true));
        assertThat(r.status()).isEqualTo(TransferStatus.REJECTED);
        assertThat(r.detail()).startsWith("AM04"); // the RTGS money does not help: instant runs on its own position
        assertThat(network.to(b)).isEmpty();
    }

    @Test
    void a_participant_the_simulator_plays_accepts_instant_payments_by_itself() {
        String a = bank("0", "100.00"), d = simulatedBank();
        UUID id = api.receive(pacs008(a, d, "25.00", "MPINST", null, true)).transferId();
        operator.instantTimers(); // simulated-answer-delay is 0 in tests
        assertThat(api.transfer(id).transfer().status()).isEqualTo(TransferStatus.SETTLED);
        assertThat(api.transfer(id).events()).last().satisfies(e -> assertThat(e.actor()).isEqualTo("sim/" + d));
    }

    // ------------------------------------------------------------------ helpers

    private String assets;

    private String bank(String rtgs, String instant) {
        return register(false, rtgs, instant);
    }

    private String simulatedBank() {
        return register(true, "0", "0");
    }

    private String register(boolean simulated, String rtgs, String instant) {
        int n = SEQ.getAndIncrement();
        String code = "Q" + (char) ('A' + n / 676 % 26) + (char) ('A' + n / 26 % 26) + (char) ('A' + n % 26);
        String bic = code + "DEFFXXX";
        repo.upsertParticipant(new ParticipantRow(bic, "Test bank " + code, simulated, SettlementProperties.rtgsAccount(bic),
                SettlementProperties.instantAccount(bic)));
        open(SettlementProperties.rtgsAccount(bic), bic, rtgs);
        open(SettlementProperties.instantAccount(bic), bic, instant);
        return bic;
    }

    private void open(String number, String bic, String balance) {
        if (assets == null) {
            assets = "CB-ASSETS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            ledger.openAccount(new OpenAccountCommand(assets, AccountType.SUSPENSE, "EUR", "Central bank assets", null, true));
        }
        var account = ledger.openAccount(new OpenAccountCommand(number, AccountType.VOSTRO, "EUR", "Account of " + bic, bic, false));
        Money m = Money.of(balance, "EUR");
        if (m.isPositive()) {
            long assetsId = ledger.findAccountByNumber(assets).orElseThrow().id();
            ledger.post(new PostEntryCommand("fund-" + number, EntryType.SETTLE, null, null, "Funding",
                    List.of(new PostingLine(account.id(), Direction.C, m), new PostingLine(assetsId, Direction.D, m))));
        }
    }

    private String pacs008(String payer, String payee, String amount, String system, String priority, boolean instant) {
        int n = SEQ.getAndIncrement();
        CreditTransfer t = new CreditTransfer(Uetr.generate().value(), "INSTR" + n, "E2E-" + n, payer, payee, payer, payee,
                "Rheinwerk Logistik GmbH", "DE89370400440532013000", "Brouwerij De Klok NV", "BE68539007547034",
                Money.of(amount, "EUR"), Money.of(amount, "EUR"), List.of(), ChargeBearer.SHAR, TODAY, SettlementMethod.CLRG,
                null, List.of(), null, new Clearing(system, priority, instant, CB));
        return messaging.buildPacs008(t);
    }

    private String answer(String payee, TransferView t, TransactionStatus status) {
        return messaging.buildPacs002(new StatusReport(payee, CB, t.msgId(), t.instructionId(), t.endToEndId(),
                t.uetr().toString(), status, null, null));
    }

    private java.math.BigDecimal rtgs(String bic) {
        return ledger.findAccountByNumber(SettlementProperties.rtgsAccount(bic)).orElseThrow().booked().amount();
    }

    private Views.InstantPosition instant(String bic) {
        return api.instant(bic);
    }

    private static <T> List<T> race(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
