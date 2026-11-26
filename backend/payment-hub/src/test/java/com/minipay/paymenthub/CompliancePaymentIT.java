package com.minipay.paymenthub;

import static org.assertj.core.api.Assertions.assertThat;

import com.minipay.common.iso.ChargeBearer;
import com.minipay.common.money.Money;
import com.minipay.compliance.api.CaseView;
import com.minipay.compliance.api.ComplianceApi;
import com.minipay.compliance.api.DecideCommand;
import com.minipay.compliance.api.DecisionAction;
import com.minipay.compliance.api.DecisionReason;
import com.minipay.ledger.api.AccountView;
import com.minipay.ledger.api.Direction;
import com.minipay.ledger.api.EntryType;
import com.minipay.ledger.api.PostEntryCommand;
import com.minipay.ledger.api.PostingLine;
import com.minipay.messaging.api.CoverTransfer;
import com.minipay.messaging.api.CreditTransfer;
import com.minipay.messaging.api.MessageDirection;
import com.minipay.messaging.api.MessageType;
import com.minipay.messaging.api.MessageView;
import com.minipay.messaging.api.MessagingApi;
import com.minipay.messaging.api.SettlementMethod;
import com.minipay.paymenthub.api.CreatePaymentCommand;
import com.minipay.paymenthub.api.PaymentStatus;
import com.minipay.paymenthub.api.PaymentView;
import com.minipay.paymenthub.api.StateChangeView;
import com.minipay.paymenthub.internal.processing.RecoverySweeper;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Payments held by sanctions screening (the real compliance module and its seeded, simulated lists):
 * nothing moves while held; a release continues the payment where screening left it; a rejection
 * ends it with RR04. Outgoing and incoming.
 */
class CompliancePaymentIT extends PaymentHubIntegrationTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 24);
    static final String US = "MNPAMYKLXXX";
    static final String FP = "Different entity: GB company reg 09811234, Leeds address; the listed party is a UAE free-zone company";
    static final String TM = "Confirmed with the relationship manager: this is the listed company";

    @Autowired
    ComplianceApi compliance;

    @Autowired
    MessagingApi messaging;

    @Autowired
    RecoverySweeper sweeper;

    @Autowired
    JdbcClient jdbc;

    // ------------------------------------------------------------------ outgoing

    @Test
    void an_outgoing_hit_is_held_without_reserving_and_is_sent_once_two_people_release_it() {
        AccountView debtor = customer("GBP", "10000.00");
        PaymentView p = hub.create(oakridge(debtor)).value();

        assertThat(p.status()).isEqualTo(PaymentStatus.HELD_COMPLIANCE);
        assertThat(reload(debtor).reserved().isZero()).isTrue();
        assertThat(messages(p, MessageType.PACS_008)).isEmpty();
        assertThat(last(p).detail()).startsWith("Held for review: Cdtr/Nm “Oakridge Interiors Ltd” ≈ OAKRIDGE INTERIORS TRADING FZE")
                .endsWith("release needs four eyes");

        UUID caseId = caseOf(p).id();
        decide(caseId, DecisionAction.RELEASE, DecisionReason.FALSE_POSITIVE, FP, "n.rahman");
        assertThat(hub.get(p.uetr()).payment().status()).isEqualTo(PaymentStatus.HELD_COMPLIANCE); // one person is not enough

        decide(caseId, DecisionAction.APPROVE, null, FP, "j.tan");

        PaymentView sent = hub.get(p.uetr()).payment();
        assertThat(sent.status()).isEqualTo(PaymentStatus.SENT);
        assertThat(messages(p, MessageType.PACS_008)).hasSize(1);
        StateChangeView release = hub.get(p.uetr()).history().stream()
                .filter(h -> h.from() == PaymentStatus.HELD_COMPLIANCE).findFirst().orElseThrow();
        assertThat(release.to()).isEqualTo(PaymentStatus.FUNDS_RESERVED);
        assertThat(release.actor()).isEqualTo("compliance/n.rahman+j.tan");
        assertThat(release.detail()).startsWith("Compliance: released by n.rahman, approved by j.tan (false positive): “Different entity")
                .contains("Hold GBP");
    }

    @Test
    void an_outgoing_true_match_is_rejected_RR04_and_no_money_was_ever_reserved() {
        AccountView debtor = customer("GBP", "10000.00");
        Money before = reload(debtor).booked();
        PaymentView p = hub.create(halvorsen(debtor)).value();
        assertThat(p.status()).isEqualTo(PaymentStatus.HELD_COMPLIANCE);

        decide(caseOf(p).id(), DecisionAction.REJECT, DecisionReason.TRUE_MATCH, TM, "n.rahman");

        PaymentView rejected = hub.get(p.uetr()).payment();
        assertThat(rejected.status()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(rejected.rejectionReason()).isEqualTo("RR04");
        assertThat(last(rejected).actor()).isEqualTo("compliance/n.rahman");
        assertThat(reload(debtor).booked()).isEqualTo(before);
        assertThat(reload(debtor).reserved().isZero()).isTrue();
        assertThat(messages(p, MessageType.PACS_008)).isEmpty();
    }

    /**
     * The decision is committed, but resuming the payment fails (the process dies right after the
     * commit, or the hub is briefly unavailable). The recovery sweeper resumes it from the committed
     * decision. Proved by mutation: without HELD_COMPLIANCE among the sweeper's statuses, the payment
     * stays held for ever although it was released.
     */
    @Test
    void a_release_that_could_not_be_acted_on_is_picked_up_by_the_sweeper() {
        AccountView debtor = customer("GBP", "10000.00");
        PaymentView p = hub.create(halvorsen(debtor)).value();

        screening.crashVerdict(true);
        decide(caseOf(p).id(), DecisionAction.RELEASE, DecisionReason.FALSE_POSITIVE, FP, "n.rahman");
        assertThat(hub.get(p.uetr()).payment().status()).isEqualTo(PaymentStatus.HELD_COMPLIANCE);

        screening.crashVerdict(false); // "restart"
        clock.advance(Duration.ofMinutes(5));
        try {
            sweeper.resumeStuckPayments();
        } finally {
            clock.advance(Duration.ofMinutes(-5));
        }
        assertThat(hub.get(p.uetr()).payment().status()).isEqualTo(PaymentStatus.SENT);
    }

    // ------------------------------------------------------------------ incoming

    @Test
    void an_incoming_hit_books_nothing_and_answers_nothing_until_released_then_credits_and_confirms() {
        AccountView vostroB = fundedVostro("T-VOSTRO-MYR-MNPB", "5000.00");
        AccountView creditor = customer("MYR", "0.01");
        Money vostroBefore = reload(vostroB).booked();
        CreditTransfer t = transfer("Halvorsen Shipping AS", "NO9386011117947", "Selangor Precision Components Sdn Bhd",
                creditor.accountNumber(), "1000.00");

        PaymentView p = hub.receiveMessage(key(), messaging.buildPacs008(t)).value();

        assertThat(p.status()).isEqualTo(PaymentStatus.HELD_COMPLIANCE);
        assertThat(reload(vostroB).booked()).isEqualTo(vostroBefore);
        assertThat(reload(creditor).booked()).isEqualTo(myr("0.01"));
        assertThat(messages(p, MessageType.PACS_002)).isEmpty();
        assertThat(caseOf(p).hits().get(0).field()).isEqualTo("Dbtr/Nm");
        assertThat(caseOf(p).direction()).isEqualTo("INCOMING");

        decide(caseOf(p).id(), DecisionAction.RELEASE, DecisionReason.FALSE_POSITIVE, FP, "n.rahman");

        PaymentView settled = hub.get(p.uetr()).payment();
        assertThat(settled.status()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(reload(creditor).booked()).isEqualTo(myr("998.01")); // 0.01 + 1000 − our 2.00
        assertThat(messages(p, MessageType.PACS_002)).singleElement().satisfies(m -> assertThat(m.status()).isEqualTo("ACSC"));
        assertThat(last(settled).actor()).isEqualTo("compliance/n.rahman");
    }

    @Test
    void an_incoming_true_match_is_refused_RR04_without_telling_the_sender_why() {
        AccountView vostroB = fundedVostro("T-VOSTRO-MYR-MNPB", "5000.00");
        Money vostroBefore = reload(vostroB).booked();
        CreditTransfer t = transfer("Halvorsen Shipping AS", "NO9386011117947", "Selangor Precision Components Sdn Bhd",
                customer("MYR", "0.01").accountNumber(), "1000.00");
        PaymentView p = hub.receiveMessage(key(), messaging.buildPacs008(t)).value();

        decide(caseOf(p).id(), DecisionAction.REJECT, DecisionReason.TRUE_MATCH, TM, "n.rahman");

        PaymentView rejected = hub.get(p.uetr()).payment();
        assertThat(rejected.status()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(rejected.rejectionReason()).isEqualTo("RR04");
        assertThat(reload(vostroB).booked()).isEqualTo(vostroBefore);
        MessageView rjct = messages(p, MessageType.PACS_002).get(0);
        assertThat(rjct.status()).isEqualTo("RJCT");
        assertThat(rjct.reasonCode()).isEqualTo("RR04");
        String raw = messaging.get(rjct.id()).raw();
        assertThat(raw).containsPattern("AddtlInf>Regulatory reason</[a-z]*:?AddtlInf>").doesNotContain("HALVORSEN MARITIME").doesNotContain("EU.SIM");
    }

    @Test
    void as_cover_correspondent_we_screen_the_underlying_customers() {
        fundedVostro("T-VOSTRO-MYR-MNPC", "5000.00");
        CreditTransfer underlying = new CreditTransfer(UUID.randomUUID().toString(), instrId(BANK_C), "INV-COV", BANK_C, BANK_B,
                BANK_C, BANK_B, "Muar Furniture Works Sdn Bhd", "GB86MNPC40051288334410", "Oakridge Interiors Ltd",
                "DE13500100600412280017", myr("700.00"), myr("700.00"), List.of(), ChargeBearer.SHAR, TODAY,
                SettlementMethod.COVE, US, List.of(), "Invoice");
        String pacs009 = messaging.buildPacs009(new CoverTransfer(underlying.uetr(), underlying.instructionId(),
                underlying.endToEndId(), BANK_C, US, BANK_C, BANK_B, myr("700.00"), TODAY, underlying));

        PaymentView p = hub.receiveMessage(key(), pacs009).value();

        assertThat(p.status()).isEqualTo(PaymentStatus.HELD_COMPLIANCE);
        assertThat(caseOf(p).hits().get(0).field()).isEqualTo("UndrlygCstmrCdtTrf/Cdtr/Nm");
        assertThat(caseOf(p).kind()).isEqualTo("COVER");
    }

    // ------------------------------------------------------------------ helpers

    private CreatePaymentCommand oakridge(AccountView debtor) {
        return new CreatePaymentCommand(key(), debtor.accountNumber(), "Oakridge Interiors Ltd", "GB86MNPC40051288334410",
                BANK_C, Money.of("7840.00", "GBP"), ChargeBearer.SHAR, null, "INV-OAK-1", "Showroom fit-out");
    }

    /** Scores below the four-eyes threshold: one officer decides. */
    private CreatePaymentCommand halvorsen(AccountView debtor) {
        return new CreatePaymentCommand(key(), debtor.accountNumber(), "Halvorsen Shipping AS", "GB86MNPC40051288334410",
                BANK_C, Money.of("1890.00", "GBP"), ChargeBearer.SHAR, null, "INV-HAL-1", "Freight");
    }

    private CaseView caseOf(PaymentView p) {
        return compliance.caseForPayment(p.id()).orElseThrow();
    }

    private void decide(UUID caseId, DecisionAction action, DecisionReason reason, String comment, String by) {
        compliance.decide(new DecideCommand(key(), caseId, action, reason, comment, by));
    }

    private StateChangeView last(PaymentView p) {
        List<StateChangeView> h = hub.get(p.uetr()).history();
        return h.get(h.size() - 1);
    }

    private List<MessageView> messages(PaymentView p, MessageType type) {
        return messaging.forUetr(p.uetr()).stream().filter(m -> m.type() == type && m.direction() == MessageDirection.OUTBOUND).toList();
    }

    /** A customer payment Bank B sends us (INDA: debit its vostro with us). */
    private CreditTransfer transfer(String debtor, String debtorAccount, String creditor, String creditorAccount, String amount) {
        Money m = myr(amount);
        return new CreditTransfer(UUID.randomUUID().toString(), instrId(BANK_B), "INV-" + ThreadLocalRandom.current().nextInt(100000),
                BANK_B, US, BANK_B, US, debtor, debtorAccount, creditor, creditorAccount, m, m, List.of(), ChargeBearer.SHAR,
                TODAY, SettlementMethod.INDA, null, List.of(), "Invoice");
    }

    private static String instrId(String bank) {
        return bank.substring(0, 4) + String.format("%012d", ThreadLocalRandom.current().nextLong(1_000_000_000_000L));
    }

    private AccountView fundedVostro(String number, String amount) {
        AccountView vostro = ledger.findAccountByNumber(number).orElseThrow();
        AccountView rtgs = ledger.findAccountByNumber("T-NOSTRO-MYR-CB").orElseThrow();
        Money m = Money.of(amount, vostro.currency());
        ledger.post(new PostEntryCommand("fund-" + UUID.randomUUID(), EntryType.SETTLE, null, null, "Test funding",
                List.of(new PostingLine(rtgs.id(), Direction.D, m), new PostingLine(vostro.id(), Direction.C, m))));
        return vostro;
    }

    private static Money myr(String amount) {
        return Money.of(amount, "MYR");
    }
}
