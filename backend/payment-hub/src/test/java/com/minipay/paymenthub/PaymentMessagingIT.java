package com.minipay.paymenthub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minipay.common.iso.ChargeBearer;
import com.minipay.common.money.Money;
import com.minipay.ledger.api.AccountView;
import com.minipay.ledger.api.EntryType;
import com.minipay.ledger.api.JournalEntryView;
import com.minipay.messaging.api.MessageDirection;
import com.minipay.messaging.api.MessageType;
import com.minipay.messaging.api.MessageView;
import com.minipay.messaging.api.MessagingApi;
import com.minipay.messaging.api.PaymentReturn;
import com.minipay.messaging.api.StatusReport;
import com.minipay.messaging.api.TransactionStatus;
import com.minipay.paymenthub.api.CreatePaymentCommand;
import com.minipay.paymenthub.api.NetworkEvent;
import com.minipay.paymenthub.api.PaymentError;
import com.minipay.paymenthub.api.PaymentPreview;
import com.minipay.paymenthub.api.PaymentStatus;
import com.minipay.paymenthub.api.PaymentView;
import com.minipay.paymenthub.api.StateChangeView;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The hub and the message gateway together: what goes out, what comes in, and what it does to payments. */
class PaymentMessagingIT extends PaymentHubIntegrationTest {

    @Autowired
    MessagingApi messaging;

    private PaymentView sent(String amount) {
        PaymentView p = hub.create(payment(customer("EUR", "100000.00"), amount, ChargeBearer.SHAR)).value();
        assertThat(p.status()).isEqualTo(PaymentStatus.SENT);
        return p;
    }

    private MessageView pacs008(PaymentView p) {
        List<MessageView> out = messaging.forUetr(p.uetr()).stream()
                .filter(m -> m.type() == MessageType.PACS_008 && m.direction() == MessageDirection.OUTBOUND).toList();
        assertThat(out).hasSize(1);
        return out.get(0);
    }

    /** What the bank we sent the payment to would tell us. */
    private String statusFrom(String bic, PaymentView p, TransactionStatus status) {
        return messaging.buildPacs002(new StatusReport(bic, "MNPAMYKLXXX", pacs008(p).msgId(), p.instructionId(),
                p.endToEndId(), p.uetr(), status, status == TransactionStatus.RJCT ? "AC04" : null, null));
    }

    // ------------------------------------------------------------------ outbound

    @Test
    void sending_a_payment_issues_exactly_one_pacs008_to_the_next_bank() {
        PaymentView p = sent("1250.00");
        MessageView m = pacs008(p);
        assertThat(m.receiverBic()).isEqualTo(BANK_B);            // serial: to our correspondent
        assertThat(m.amount()).isEqualTo(eur("1250.00"));
        assertThat(m.instructionId()).isEqualTo(p.instructionId()).hasSize(16);
        String raw = messaging.get(m.id()).raw();
        assertThat(raw).contains("<pacs:SttlmMtd>INDA</pacs:SttlmMtd>")   // our nostro at Bank B
                .contains("<pacs:BICFI>" + BANK_C + "</pacs:BICFI>");    // creditor agent
        assertThat(hub.get(p.uetr()).history().get(4).detail()).startsWith("pacs.008 " + m.msgId() + " → " + BANK_B);
    }

    @Test
    void a_payment_credited_to_a_vostro_settles_on_our_books_INGA() {
        AccountView debtor = customer("MYR", "5000.00");
        PaymentView p = hub.create(new CreatePaymentCommand(key(), debtor.accountNumber(), "Kessler Maschinenbau GmbH",
                "DE13500100600412280017", BANK_B, Money.of("100.00", "MYR"), ChargeBearer.SHAR, null, null, null)).value();
        assertThat(messaging.get(pacs008(p).id()).raw()).contains("<pacs:SttlmMtd>INGA</pacs:SttlmMtd>");
    }

    @Test
    void cred_explains_the_deducted_fee_in_chrgsinf() {
        PaymentView p = hub.create(payment(customer("EUR", "1000.00"), "1000.00", ChargeBearer.CRED)).value();
        String raw = messaging.get(pacs008(p).id()).raw();
        assertThat(raw).contains("<pacs:IntrBkSttlmAmt Ccy=\"EUR\">980.00</pacs:IntrBkSttlmAmt>")
                .contains("<pacs:InstdAmt Ccy=\"EUR\">1000.00</pacs:InstdAmt>")
                .containsPattern("<pacs:ChrgsInf>\\s*<pacs:Amt Ccy=\"EUR\">20.00</pacs:Amt>");
    }

    // ------------------------------------------------------------------ inbound

    @Test
    void a_pacs002_from_the_next_bank_acknowledges_the_payment_and_a_resend_changes_nothing() {
        PaymentView p = sent("500.00");
        String raw = statusFrom(BANK_B, p, TransactionStatus.ACCP);

        PaymentView acked = hub.receiveMessage(key(), raw).value();
        var resent = hub.receiveMessage(key(), raw); // the same message again, under a new key

        assertThat(acked.status()).isEqualTo(PaymentStatus.ACKED);
        assertThat(resent.replayed()).isTrue();
        List<StateChangeView> history = hub.get(p.uetr()).history();
        assertThat(history).extracting(StateChangeView::to).containsOnlyOnce(PaymentStatus.ACKED);
        assertThat(history.get(history.size() - 1).actor()).isEqualTo("net/" + BANK_B);
        assertThat(messaging.forUetr(p.uetr())).extracting(MessageView::type)
                .containsExactly(MessageType.PACS_008, MessageType.PACS_002); // stored once
    }

    @Test
    void a_status_that_arrives_after_a_later_one_does_not_move_the_payment_back() {
        PaymentView p = sent("500.00");
        hub.receiveMessage(key(), statusFrom(BANK_B, p, TransactionStatus.ACCP));
        hub.receiveMessage(key(), statusFrom(BANK_B, p, TransactionStatus.ACSC));
        // A second ACCP (a new message, not a resend) that arrives after the settlement confirmation:
        var late = hub.receiveMessage(key(), statusFrom(BANK_B, p, TransactionStatus.ACCP));
        assertThat(late.value().status()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(messaging.forUetr(p.uetr())).hasSize(4); // every report is kept as evidence
    }

    @Test
    void a_report_from_a_bank_we_did_not_send_to_is_refused_but_kept() {
        PaymentView p = sent("500.00");
        String fromC = statusFrom(BANK_C, p, TransactionStatus.ACCP);
        assertThatThrownBy(() -> hub.receiveMessage(key(), fromC))
                .extracting("error").isEqualTo(PaymentError.INVALID_NETWORK_EVENT);
        assertThat(hub.get(p.uetr()).payment().status()).isEqualTo(PaymentStatus.SENT);
        assertThat(messaging.forUetr(p.uetr())).extracting(MessageView::senderBic).contains(BANK_C);
    }

    @Test
    void an_invalid_message_is_refused_and_kept_as_invalid() {
        PaymentView p = sent("500.00");
        String broken = statusFrom(BANK_B, p, TransactionStatus.ACCP).replace("<pacs:TxSts>ACCP", "<pacs:TxSts>XXXX");
        assertThatThrownBy(() -> hub.receiveMessage(key(), broken))
                .extracting("error").isEqualTo(PaymentError.INVALID_MESSAGE);
        assertThat(messaging.forUetr(p.uetr())).extracting(MessageView::status).contains("INVALID");
    }

    @Test
    void a_rejection_after_sent_is_stored_but_not_acted_on_until_m5() {
        PaymentView p = sent("500.00");
        assertThatThrownBy(() -> hub.receiveMessage(key(), statusFrom(BANK_B, p, TransactionStatus.RJCT)))
                .extracting("error").isEqualTo(PaymentError.UNSUPPORTED_MESSAGE);
        assertThat(hub.get(p.uetr()).payment().status()).isEqualTo(PaymentStatus.SENT);
    }

    @Test
    void a_pacs004_returns_the_money_once_even_if_it_arrives_twice() {
        AccountView debtor = customer("EUR", "10000.00");
        PaymentView p = hub.create(payment(debtor, "1000.00", ChargeBearer.SHAR)).value();
        hub.receiveMessage(key(), statusFrom(BANK_B, p, TransactionStatus.ACCP));
        hub.receiveMessage(key(), statusFrom(BANK_B, p, TransactionStatus.ACSC));
        String pacs004 = messaging.buildPacs004(new PaymentReturn(BANK_B, "MNPAMYKLXXX", "MNPC-RTN-1", pacs008(p).msgId(),
                p.instructionId(), p.endToEndId(), p.uetr(), eur("1000.00"), eur("975.00"), LocalDate.of(2026, 11, 24),
                List.of(new PaymentReturn.Charge(eur("25.00"), BANK_B)), "AC04", "Account closed"));

        PaymentView returned = hub.receiveMessage(key(), pacs004).value();
        hub.receiveMessage(key(), pacs004);

        assertThat(returned.status()).isEqualTo(PaymentStatus.RETURNED);
        assertThat(returned.rejectionReason()).isEqualTo("AC04");
        assertThat(ledger.entriesForPayment(p.id())).extracting(JournalEntryView::entryType)
                .containsExactly(EntryType.RESERVE, EntryType.RELEASE, EntryType.SETTLE, EntryType.FEE, EntryType.SETTLE);
        assertThat(reload(debtor).booked()).isEqualTo(eur("9955.00")); // 10,000 − 1,000 − 20 fee + 975 back
    }

    @Test
    void simulated_network_events_are_real_messages_from_the_next_bank() {
        PaymentView p = sent("500.00");
        hub.onNetworkEvent(key(), p.uetr(), new NetworkEvent(NetworkEvent.Type.ACCEPTED, null, null, null));
        hub.onNetworkEvent(key(), p.uetr(), new NetworkEvent(NetworkEvent.Type.SETTLED, null, null, null));
        hub.onNetworkEvent(key(), p.uetr(), new NetworkEvent(NetworkEvent.Type.RETURNED, "AC04", eur("480.00"), "closed"));
        List<MessageView> msgs = messaging.forUetr(p.uetr());
        assertThat(msgs).extracting(MessageView::type)
                .containsExactly(MessageType.PACS_008, MessageType.PACS_002, MessageType.PACS_002, MessageType.PACS_004);
        assertThat(msgs.subList(1, 4)).allSatisfy(m -> {
            assertThat(m.direction()).isEqualTo(MessageDirection.INBOUND);
            assertThat(m.senderBic()).isEqualTo(BANK_B);
            assertThat(m.originalMsgId()).isEqualTo(msgs.get(0).msgId());
        });
        assertThat(msgs.get(3).amount()).isEqualTo(eur("480.00"));
    }

    // ------------------------------------------------------------------ preview

    @Test
    void the_preview_is_the_pacs008_the_payment_would_produce_and_creates_nothing() {
        AccountView debtor = customer("EUR", "1000.00");
        long before = hub.countByStatus().values().stream().mapToLong(Long::longValue).sum();
        PaymentPreview preview = hub.preview(payment(debtor, "100.00", ChargeBearer.SHAR));
        assertThat(preview.xml()).contains("<pacs:BICFI>" + BANK_B + "</pacs:BICFI>").contains("MNPAPREVIEW");
        assertThat(preview.notes()).anyMatch(n -> n.startsWith("Routed by R-803"));
        assertThat(hub.countByStatus().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(before);
        assertThat(reload(debtor).reserved().isZero()).isTrue();
    }
}
