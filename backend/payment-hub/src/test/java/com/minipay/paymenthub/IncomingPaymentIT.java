package com.minipay.paymenthub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minipay.common.iso.ChargeBearer;
import com.minipay.common.money.Money;
import com.minipay.ledger.api.AccountView;
import com.minipay.ledger.api.Direction;
import com.minipay.ledger.api.EntryType;
import com.minipay.ledger.api.PostEntryCommand;
import com.minipay.ledger.api.PostingLine;
import com.minipay.messaging.api.Charge;
import com.minipay.messaging.api.CoverTransfer;
import com.minipay.messaging.api.CreditNotification;
import com.minipay.messaging.api.CreditTransfer;
import com.minipay.messaging.api.MessageType;
import com.minipay.messaging.api.MessageView;
import com.minipay.messaging.api.MessagingApi;
import com.minipay.messaging.api.PaymentReturn;
import com.minipay.messaging.api.SettlementMethod;
import com.minipay.messaging.api.StatusReport;
import com.minipay.messaging.api.TransactionStatus;
import com.minipay.paymenthub.api.PaymentDirection;
import com.minipay.paymenthub.api.PaymentError;
import com.minipay.paymenthub.api.PaymentKind;
import com.minipay.paymenthub.api.PaymentStatus;
import com.minipay.paymenthub.api.PaymentView;
import com.minipay.refdata.api.CreateRoutingRuleCommand;
import com.minipay.refdata.api.RoutingMethod;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Bank A receiving from the network: as the creditor's bank, as an intermediary, and in both roles of a
 * cover payment. The other banks are played by building the messages they would send.
 */
class IncomingPaymentIT extends PaymentHubIntegrationTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 24);
    static final String US = "MNPAMYKLXXX";

    @Autowired
    MessagingApi messaging;

    @Autowired
    JdbcClient jdbc;

    // ------------------------------------------------------------------ as the creditor's bank

    @Test
    void a_payment_for_our_customer_is_credited_net_of_our_charge_and_confirmed_with_acsc() {
        AccountView vostroB = fundedVostro("T-VOSTRO-MYR-MNPB", "5000.00");
        AccountView creditor = customer("MYR", "1.00");
        Money vostroBefore = reload(vostroB).booked();
        CreditTransfer t = transfer(BANK_B, "MYR", "1000.00", US, creditor.accountNumber(), SettlementMethod.INDA, null);

        PaymentView p = hub.receiveMessage(key(), messaging.buildPacs008(t)).value();

        assertThat(p.direction()).isEqualTo(PaymentDirection.INCOMING);
        assertThat(p.status()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(p.uetr()).isEqualTo(t.uetr());
        assertThat(p.instructingAgentBic()).isEqualTo(BANK_B);
        assertThat(p.chargesAmount()).isEqualTo(myr("2.00"));
        assertThat(reload(creditor).booked()).isEqualTo(myr("999.00"));            // 1 + 1000 − 2
        assertThat(reload(vostroB).booked()).isEqualTo(vostroBefore.minus(myr("1000.00")));
        assertThat(sent(p)).containsExactly("pacs.002.001.10 → MNPBDEFFXXX ACSC");
    }

    @Test
    void the_same_message_twice_is_one_payment_and_a_second_payment_under_its_uetr_is_refused() {
        fundedVostro("T-VOSTRO-MYR-MNPB", "5000.00");
        AccountView creditor = customer("MYR", "1.00");
        CreditTransfer t = transfer(BANK_B, "MYR", "100.00", US, creditor.accountNumber(), SettlementMethod.INDA, null);
        String raw = messaging.buildPacs008(t);

        PaymentView first = hub.receiveMessage(key(), raw).value();
        var again = hub.receiveMessage(key(), raw); // redelivered by the network under another event id
        assertThat(again.replayed()).isTrue();
        assertThat(again.value().id()).isEqualTo(first.id());
        assertThat(reload(creditor).booked()).isEqualTo(myr("99.00")); // credited once

        String other = messaging.buildPacs008(t); // a new MsgId, the same UETR
        assertThatThrownBy(() -> hub.receiveMessage(key(), other)).extracting("error").isEqualTo(PaymentError.INVALID_MESSAGE);
    }

    @Test
    void an_unknown_creditor_account_is_rejected_before_anything_is_booked() {
        AccountView vostroB = fundedVostro("T-VOSTRO-MYR-MNPB", "5000.00");
        Money before = reload(vostroB).booked();
        PaymentView p = hub.receiveMessage(key(), messaging.buildPacs008(
                transfer(BANK_B, "MYR", "100.00", US, "NO-SUCH-ACCOUNT", SettlementMethod.INDA, null))).value();
        assertThat(p.status()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(p.rejectionReason()).isEqualTo("AC01");
        assertThat(reload(vostroB).booked()).isEqualTo(before);
        assertThat(ledger.entriesForPayment(p.id())).isEmpty();
        assertThat(sent(p)).containsExactly("pacs.002.001.10 → MNPBDEFFXXX RJCT");
    }

    @Test
    void a_sender_without_the_money_on_its_account_with_us_is_rejected_AM04() {
        AccountView creditor = customer("MYR", "1.00");
        long balance = reload(ledger.findAccountByNumber("T-VOSTRO-MYR-MNPC").orElseThrow()).booked().amount().longValue();
        PaymentView p = hub.receiveMessage(key(), messaging.buildPacs008(transfer(BANK_C, "MYR",
                (balance + 1_000_000) + ".00", US, creditor.accountNumber(), SettlementMethod.INDA, null))).value();
        assertThat(p.status()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(p.rejectionReason()).isEqualTo("AM04");
        assertThat(reload(creditor).booked()).isEqualTo(myr("1.00"));
    }

    @Test
    void we_can_return_a_payment_we_credited_and_the_return_lists_our_charge() {
        AccountView vostroB = fundedVostro("T-VOSTRO-MYR-MNPB", "5000.00");
        AccountView creditor = customer("MYR", "1.00");
        PaymentView p = hub.receiveMessage(key(), messaging.buildPacs008(
                transfer(BANK_B, "MYR", "1000.00", US, creditor.accountNumber(), SettlementMethod.INDA, null))).value();
        Money vostroBefore = reload(vostroB).booked();

        PaymentView returned = hub.returnPayment(key(), p.uetr(), "AC04", "Account closed").value();

        assertThat(returned.status()).isEqualTo(PaymentStatus.RETURNED);
        assertThat(reload(creditor).booked()).isEqualTo(myr("1.00"));
        assertThat(reload(vostroB).booked()).isEqualTo(vostroBefore.plus(myr("998.00")));
        MessageView pacs004 = outbound(p, MessageType.PACS_004);
        PaymentReturn r = messaging.paymentReturn(pacs004.id());
        assertThat(r.returnedAmount()).isEqualTo(myr("998.00"));
        assertThat(r.charges()).containsExactly(new Charge(myr("2.00"), US));
        assertThat(r.originalMsgId()).isNotNull();
    }

    // ------------------------------------------------------------------ as an intermediary

    @Test
    void as_an_intermediary_we_book_through_deduct_our_charge_and_pass_it_on() {
        AccountView vostroB = fundedVostro("T-VOSTRO-MYR-MNPB", "5000.00");
        AccountView vostroC = ledger.findAccountByNumber("T-VOSTRO-MYR-MNPC").orElseThrow();
        Money bBefore = reload(vostroB).booked();
        Money cBefore = reload(vostroC).booked();

        PaymentView p = hub.receiveMessage(key(), messaging.buildPacs008(
                transfer(BANK_B, "MYR", "1000.00", BANK_C, "GB67MNPC40051271520093", SettlementMethod.INDA, null))).value();

        assertThat(p.status()).isEqualTo(PaymentStatus.SENT);
        assertThat(p.instructedAgentBic()).isEqualTo(BANK_C);
        assertThat(reload(vostroB).booked()).isEqualTo(bBefore.minus(myr("1000.00")));
        assertThat(reload(vostroC).booked()).isEqualTo(cBefore.plus(myr("998.00")));
        assertThat(sent(p)).containsExactly("pacs.008.001.08 → MNPCGB2LXXX", "pacs.002.001.10 → MNPBDEFFXXX ACSP");
        CreditTransfer onward = messaging.creditTransfer(outbound(p, MessageType.PACS_008).id());
        assertThat(onward.instructingAgentBic()).isEqualTo(US);
        assertThat(onward.interbankAmount()).isEqualTo(myr("998.00"));
        assertThat(onward.charges()).containsExactly(new Charge(myr("2.00"), US));
        assertThat(onward.previousAgents()).containsExactly(BANK_B);
        assertThat(onward.settlementMethod()).isEqualTo(SettlementMethod.INGA); // we credit C's vostro: our books
        assertThat(onward.instructionId()).startsWith("MNPA").hasSize(16).isNotEqualTo(p.instructionId());
    }

    @Test
    void the_creditors_confirmation_travels_back_to_the_bank_before_us() {
        fundedVostro("T-VOSTRO-MYR-MNPB", "5000.00");
        PaymentView p = hub.receiveMessage(key(), messaging.buildPacs008(
                transfer(BANK_B, "MYR", "500.00", BANK_C, "GB67MNPC40051271520093", SettlementMethod.INDA, null))).value();

        PaymentView settled = hub.receiveMessage(key(), report(BANK_C, p, TransactionStatus.ACSC, null)).value();

        assertThat(settled.status()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(sent(p)).containsExactly("pacs.008.001.08 → MNPCGB2LXXX", "pacs.002.001.10 → MNPBDEFFXXX ACSP",
                "pacs.002.001.10 → MNPBDEFFXXX ACSC");
    }

    @Test
    void a_rejection_further_on_is_reversed_here_and_passed_back() {
        AccountView vostroB = fundedVostro("T-VOSTRO-MYR-MNPB", "5000.00");
        AccountView vostroC = ledger.findAccountByNumber("T-VOSTRO-MYR-MNPC").orElseThrow();
        Money bBefore = reload(vostroB).booked();
        Money cBefore = reload(vostroC).booked();
        PaymentView p = hub.receiveMessage(key(), messaging.buildPacs008(
                transfer(BANK_B, "MYR", "500.00", BANK_C, "GB67MNPC40051271520093", SettlementMethod.INDA, null))).value();

        PaymentView rejected = hub.receiveMessage(key(), report(BANK_C, p, TransactionStatus.RJCT, "AC04")).value();

        assertThat(rejected.status()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(reload(vostroB).booked()).isEqualTo(bBefore);   // both accounts as before
        assertThat(reload(vostroC).booked()).isEqualTo(cBefore);
        assertThat(sent(p)).last().isEqualTo("pacs.002.001.10 → MNPBDEFFXXX RJCT");
    }

    @Test
    void a_return_further_on_is_booked_back_and_passed_on_with_every_banks_charge() {
        AccountView vostroB = fundedVostro("T-VOSTRO-MYR-MNPB", "5000.00");
        PaymentView p = hub.receiveMessage(key(), messaging.buildPacs008(
                transfer(BANK_B, "MYR", "1000.00", BANK_C, "GB67MNPC40051271520093", SettlementMethod.INDA, null))).value();
        Money bAfterForward = reload(vostroB).booked();
        // Bank C credited 998 − its 8 = 990, then returns what it credited.
        String pacs004 = messaging.buildPacs004(new PaymentReturn(BANK_C, US, "RTN-C-1",
                outbound(p, MessageType.PACS_008).msgId(), "MNPA261124000001", p.endToEndId(), p.uetr(), myr("998.00"),
                myr("990.00"), TODAY, List.of(new Charge(myr("8.00"), BANK_C)), "AC04", "Account closed"));

        PaymentView returned = hub.receiveMessage(key(), pacs004).value();

        assertThat(returned.status()).isEqualTo(PaymentStatus.RETURNED);
        assertThat(reload(vostroB).booked()).isEqualTo(bAfterForward.plus(myr("990.00")));
        PaymentReturn back = messaging.paymentReturn(outbound(p, MessageType.PACS_004).id());
        assertThat(back.toBic()).isEqualTo(BANK_B);
        assertThat(back.returnedAmount()).isEqualTo(myr("990.00"));
        assertThat(back.charges()).containsExactly(new Charge(myr("8.00"), BANK_C), new Charge(myr("2.00"), US));
    }

    // ------------------------------------------------------------------ cover method

    @Test
    void a_cover_payment_waits_for_the_cover_and_then_pays_out() {
        AccountView creditor = customer("EUR", "1.00");
        Money nostroBefore = reload(eurNostro).booked();
        CreditTransfer t = transfer(BANK_C, "EUR", "2000.00", US, creditor.accountNumber(), SettlementMethod.COVE, BANK_B);

        PaymentView waiting = hub.receiveMessage(key(), messaging.buildPacs008(t)).value();
        assertThat(waiting.status()).isEqualTo(PaymentStatus.AWAITING_COVER);
        assertThat(reload(creditor).booked()).isEqualTo(eur("1.00")); // nothing paid out on a promise

        PaymentView paid = hub.receiveMessage(key(), coverNotice(t)).value();

        assertThat(paid.status()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(reload(creditor).booked()).isEqualTo(eur("1996.00"));         // 1 + 2000 − 5
        assertThat(reload(eurNostro).booked()).isEqualTo(nostroBefore.plus(eur("2000.00"))); // our money at Bank B grew
        assertThat(sent(paid)).containsExactly("pacs.002.001.10 → MNPCGB2LXXX ACSC");     // to the sender, not the cover agent
    }

    @Test
    void a_cover_that_arrives_before_its_payment_is_matched_when_the_payment_comes() {
        AccountView creditor = customer("EUR", "1.00");
        CreditTransfer t = transfer(BANK_C, "EUR", "300.00", US, creditor.accountNumber(), SettlementMethod.COVE, BANK_B);
        String notice = coverNotice(t);
        assertThatThrownBy(() -> hub.receiveMessage(key(), notice)).extracting("error").isEqualTo(PaymentError.PAYMENT_NOT_FOUND);

        PaymentView p = hub.receiveMessage(key(), messaging.buildPacs008(t)).value();

        assertThat(p.status()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(reload(creditor).booked()).isEqualTo(eur("296.00"));
    }

    @Test
    void as_the_cover_correspondent_we_move_the_money_between_the_banks_and_notify_the_creditor_bank() {
        AccountView vostroB = ledger.findAccountByNumber("T-VOSTRO-MYR-MNPB").orElseThrow();
        AccountView vostroC = fundedVostro("T-VOSTRO-MYR-MNPC", "5000.00");
        Money bBefore = reload(vostroB).booked();
        Money cBefore = reload(vostroC).booked();
        CreditTransfer underlying = transfer(BANK_C, "MYR", "700.00", BANK_B, "DE13500100600412280017", SettlementMethod.COVE, US);
        String pacs009 = messaging.buildPacs009(new CoverTransfer(underlying.uetr(), underlying.instructionId(),
                underlying.endToEndId(), BANK_C, US, BANK_C, BANK_B, myr("700.00"), TODAY, underlying));

        PaymentView p = hub.receiveMessage(key(), pacs009).value();

        assertThat(p.kind()).isEqualTo(PaymentKind.COVER);
        assertThat(p.status()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(reload(vostroC).booked()).isEqualTo(cBefore.minus(myr("700.00")));
        assertThat(reload(vostroB).booked()).isEqualTo(bBefore.plus(myr("700.00")));
        assertThat(sent(p)).containsExactly("camt.054.001.08 → MNPBDEFFXXX CRDT");
        CreditNotification n = messaging.creditNotification(outbound(p, MessageType.CAMT_054).id());
        assertThat(n.uetr()).isEqualTo(underlying.uetr());
        assertThat(n.account()).isEqualTo("T-VOSTRO-MYR-MNPB");
    }

    @Test
    void sending_by_cover_puts_the_payment_and_its_cover_on_the_network_together() {
        refdata.createRoutingRule(new CreateRoutingRuleCommand(key(), "MNPCGB2L200", "EUR", RoutingMethod.COVER, BANK_B,
                "T-NOSTRO-EUR-MNPB", 10, TODAY, null, null, "r.lim", "a.wong", "Manchester branch by cover"));
        PaymentView p = hub.create(to(payment(customer("EUR", "1000.00"), "100.00", ChargeBearer.SHAR),
                "GB67MNPC40051271520093", "MNPCGB2L200")).value();

        assertThat(p.status()).isEqualTo(PaymentStatus.SENT);
        assertThat(p.settlementMethod()).isEqualTo("COVE");
        assertThat(p.coverAgentBic()).isEqualTo(BANK_B);
        assertThat(sent(p)).containsExactly("pacs.008.001.08 → MNPCGB2L200", "pacs.009.001.08 → MNPBDEFFXXX");
        assertThat(messaging.creditTransfer(outbound(p, MessageType.PACS_008).id()).coverAgentBic()).isEqualTo(BANK_B);
    }

    // ------------------------------------------------------------------ helpers

    /** A customer payment sent to us by {@code from}, as that bank would build it. */
    private CreditTransfer transfer(String from, String ccy, String amount, String creditorAgent, String creditorAccount,
                                    SettlementMethod method, String coverAgent) {
        Money m = Money.of(amount, ccy);
        return new CreditTransfer(UUID.randomUUID().toString(),
                from.substring(0, 4) + String.format("%012d", ThreadLocalRandom.current().nextLong(1_000_000_000_000L)),
                "INV-" + ThreadLocalRandom.current().nextInt(100000), from, US, from, creditorAgent,
                "Kessler Maschinenbau GmbH", "DE13500100600412280017", "Selangor Precision Components Sdn Bhd",
                creditorAccount, m, m, List.of(), ChargeBearer.SHAR, TODAY, method, coverAgent, List.of(), "Invoice");
    }

    /** Bank B (the cover agent) tells us our account with it was credited for this payment. */
    private String coverNotice(CreditTransfer t) {
        return messaging.buildCamt054(new CreditNotification(BANK_B, US, "DE89500100600011223344", t.interbankAmount(),
                TODAY, java.time.Instant.now(), t.uetr(), t.endToEndId(), t.instructionId(), "B-REF-" + t.instructionId()));
    }

    /** A status report from the next bank about the payment we passed on. */
    private String report(String from, PaymentView p, TransactionStatus status, String reason) {
        return messaging.buildPacs002(new StatusReport(from, US, outbound(p, MessageType.PACS_008).msgId(),
                p.instructionId(), p.endToEndId(), p.uetr(), status, reason, null));
    }

    private AccountView fundedVostro(String number, String amount) {
        AccountView vostro = ledger.findAccountByNumber(number).orElseThrow();
        AccountView rtgs = ledger.findAccountByNumber("T-NOSTRO-MYR-CB").orElseThrow();
        Money m = Money.of(amount, vostro.currency());
        ledger.post(new PostEntryCommand("fund-" + UUID.randomUUID(), EntryType.SETTLE, null, null, "Test funding",
                List.of(new PostingLine(rtgs.id(), Direction.D, m), new PostingLine(vostro.id(), Direction.C, m))));
        return vostro;
    }

    /** What this payment put on the network, in order: "type → receiver [status]". */
    private List<String> sent(PaymentView p) {
        return jdbc.sql("""
                        SELECT payload->>'type', payload->>'receiver', payload->>'messageId' FROM payment.outbox_event
                         WHERE aggregate_id = :id AND event_type = 'network.message' ORDER BY seq
                        """)
                .param("id", p.id())
                .query((rs, n) -> {
                    MessageView m = messaging.get(UUID.fromString(rs.getString(3))).message();
                    return rs.getString(1) + " → " + rs.getString(2) + (m.status() != null ? " " + m.status() : "");
                }).list();
    }

    private MessageView outbound(PaymentView p, MessageType type) {
        return messaging.forUetr(p.uetr()).stream()
                .filter(m -> m.type() == type && m.direction() == com.minipay.messaging.api.MessageDirection.OUTBOUND)
                .reduce((a, b) -> b).orElseThrow();
    }

    private static Money myr(String amount) {
        return Money.of(amount, "MYR");
    }
}
