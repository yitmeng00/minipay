package com.minipay.paymenthub.internal.processing;

import com.minipay.common.id.Bic;
import com.minipay.compliance.api.CaseVerdict;
import com.minipay.common.iso.ChargeBearer;
import com.minipay.common.money.CurrencyUnits;
import com.minipay.common.money.Money;
import com.minipay.ledger.api.AccountStatus;
import com.minipay.ledger.api.AccountType;
import com.minipay.ledger.api.AccountView;
import com.minipay.ledger.api.Direction;
import com.minipay.ledger.api.EntryType;
import com.minipay.ledger.api.JournalEntryView;
import com.minipay.ledger.api.LedgerApi;
import com.minipay.ledger.api.LedgerException;
import com.minipay.ledger.api.PostEntryCommand;
import com.minipay.ledger.api.PostingLine;
import com.minipay.messaging.api.Charge;
import com.minipay.messaging.api.CreditNotification;
import com.minipay.messaging.api.CreditTransfer;
import com.minipay.messaging.api.MessageDirection;
import com.minipay.messaging.api.MessageType;
import com.minipay.messaging.api.MessageView;
import com.minipay.messaging.api.MessagingApi;
import com.minipay.messaging.api.StatusReport;
import com.minipay.messaging.api.TransactionStatus;
import com.minipay.paymenthub.api.PaymentKind;
import com.minipay.paymenthub.api.PaymentStatus;
import com.minipay.paymenthub.internal.config.PaymentHubProperties;
import com.minipay.paymenthub.internal.domain.Payment;
import com.minipay.paymenthub.internal.network.NetworkGateway;
import com.minipay.paymenthub.internal.processing.PaymentTransitions.Change;
import com.minipay.refdata.api.RefDataApi;
import com.minipay.refdata.api.RoutingMethod;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Drives a payment another bank sent us: RECEIVED → VALIDATED → SCREENED → one of
 * <ul>
 *   <li><b>SETTLED</b>: we are the creditor's bank; we credited our customer and told the sender
 *       (pacs.002 ACSC);</li>
 *   <li><b>AWAITING_COVER</b> → SETTLED: the same, but the money comes separately (cover method), so we
 *       wait for the correspondent's camt.054 before paying out;</li>
 *   <li><b>SENT</b>: we are an intermediary; we booked it through, deducted our charge, and passed it on
 *       to the next bank (pacs.008), telling the sender we accepted it (pacs.002 ACSP);</li>
 *   <li><b>SETTLED</b> for a {@link PaymentKind#COVER} payment: we are the correspondent of a cover
 *       payment (pacs.009); we moved the money from the debtor's bank's account to the creditor's
 *       bank's account, and notified the creditor's bank (camt.054).</li>
 * </ul>
 * A payment we cannot take is REJECTED before anything is booked, and the sender is told why (pacs.002
 * RJCT with an ISO reason).
 *
 * <h2>Which account?</h2>
 * The message's settlement method says where the money arrives:
 * <ul>
 *   <li><b>INDA</b>: on our books. We debit the account the sender holds with us (its vostro).</li>
 *   <li><b>INGA</b>: on the sender's books. It credited the account we hold with it (our nostro there), so we
 *       debit that nostro: our asset at the sender grew.</li>
 *   <li><b>CLRG</b>: through the RTGS system. We debit our central bank account, the one our routing table
 *       uses for RTGS in that currency.</li>
 *   <li><b>COVE</b>: through the cover agent named in the message. We debit our nostro with it, once its
 *       camt.054 says the money is there.</li>
 * </ul>
 *
 * <p>The same crash-safety rules as outgoing payments apply (ADR-003 §3): every ledger call and every
 * message has a key derived from the payment, so re-running a step replays instead of repeating.
 */
@Component
public class IncomingProcessor {

    private static final Logger log = LoggerFactory.getLogger(IncomingProcessor.class);

    private final PaymentTransitions transitions;
    private final LedgerApi ledger;
    private final RefDataApi refdata;
    private final Router router;
    private final MessagingApi messaging;
    private final NetworkGateway network;
    private final ScreeningPort screening;
    private final InstructionIds instructionIds;
    private final PaymentHubProperties props;
    private final Clock clock;
    private final ZoneId bankZone;
    private final String ownBic;

    public IncomingProcessor(PaymentTransitions transitions, LedgerApi ledger, RefDataApi refdata, Router router,
                             MessagingApi messaging, NetworkGateway network, ScreeningPort screening,
                             InstructionIds instructionIds, PaymentHubProperties props, ObjectProvider<Clock> clock,
                             @Value("${minipay.bank.time-zone:UTC}") ZoneId bankZone,
                             @Value("${minipay.bank.bic}") String ownBic) {
        this.transitions = transitions;
        this.ledger = ledger;
        this.refdata = refdata;
        this.router = router;
        this.messaging = messaging;
        this.network = network;
        this.screening = screening;
        this.instructionIds = instructionIds;
        this.props = props;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
        this.bankZone = bankZone;
        this.ownBic = Bic.parse(ownBic).value();
    }

    /** One step for an incoming payment. @return true if the payment moved on */
    boolean step(Payment p) {
        return switch (p.status()) {
            case RECEIVED -> validate(p);
            case VALIDATED -> screen(p);
            case SCREENED -> act(p, Resume.SCREENED);
            case HELD_COMPLIANCE -> afterCompliance(p);
            case AWAITING_COVER -> settleCover(p);
            default -> false; // final, or forwarded (waiting for the next bank)
        };
    }

    // ------------------------------------------------------------------ steps

    private boolean validate(Payment p) {
        Optional<AccountView> inbound = inboundAccount(p);
        if (inbound.isEmpty()) {
            return reject(p, PaymentStatus.RECEIVED, "AG01", "We hold no " + p.currency() + " account that "
                    + p.settlementMethod() + " settlement with " + settlingBank(p) + " could use");
        }
        String detail;
        if (p.kind() == PaymentKind.COVER) {
            if (bankAccount(AccountType.VOSTRO, p.creditorAgentBic(), p.currency()).isEmpty()) {
                return reject(p, PaymentStatus.RECEIVED, "AC01", "We hold no " + p.currency() + " account for "
                        + p.creditorAgentBic() + ", the bank to be credited");
            }
            detail = "Cover payment: debit " + inbound.get().accountNumber() + " (" + p.instructingAgentBic()
                    + "), credit the account of " + p.creditorAgentBic();
        } else if (isUs(p.creditorAgentBic())) {
            Optional<AccountView> creditor = ledger.findAccountByNumber(p.creditorAccount());
            if (creditor.isEmpty() || creditor.get().type() != AccountType.CUSTOMER) {
                return reject(p, PaymentStatus.RECEIVED, "AC01", "Creditor account " + p.creditorAccount() + " is not a customer account here");
            }
            if (creditor.get().status() == AccountStatus.CLOSED) {
                return reject(p, PaymentStatus.RECEIVED, "AC04", "Creditor account is closed");
            }
            if (creditor.get().status() == AccountStatus.BLOCKED) {
                return reject(p, PaymentStatus.RECEIVED, "AC06", "Creditor account is blocked");
            }
            if (!creditor.get().currency().equals(p.currency())) {
                return reject(p, PaymentStatus.RECEIVED, "AM03", "Creditor account is in " + creditor.get().currency());
            }
            detail = "We are the creditor's bank: credit " + p.creditorAccount() + " (" + creditor.get().ownerName() + ")"
                    + ("COVE".equals(p.settlementMethod()) ? " once the cover arrives via " + p.coverAgentBic() : "");
        } else {
            if ("COVE".equals(p.settlementMethod())) {
                return reject(p, PaymentStatus.RECEIVED, "AG01", "A cover payment must be addressed to the creditor's bank");
            }
            Router.Outcome route = router.route(p.creditorAgentBic(), p.currency(), p.executionDate());
            if (route.route().isEmpty()) {
                return reject(p, PaymentStatus.RECEIVED, "CNOR", "No route to " + p.creditorAgentBic() + ": " + route.problem());
            }
            if (route.route().get().method() == RoutingMethod.COVER) {
                return reject(p, PaymentStatus.RECEIVED, "CNOR", "Our route to " + p.creditorAgentBic()
                        + " is by cover; an intermediary adding a cover leg is not modelled");
            }
            detail = "We are an intermediary: pass on to " + route.route().get().instructedAgentBic() + " by "
                    + route.route().get().describe();
        }
        return transitions.transition(p.id(), PaymentStatus.RECEIVED, payment ->
                new Change(PaymentStatus.VALIDATED, null, detail, "svc/validator"));
    }

    /** Every bank screens for itself (ADR-007): we hold it even if the banks before us let it through. */
    private boolean screen(Payment p) {
        ScreeningPort.Result result = screening.screen(p, LocalDate.now(clock.withZone(bankZone)));
        return transitions.transition(p.id(), PaymentStatus.VALIDATED, payment ->
                new Change(PaymentStatus.SCREENED, null, result.detail(), "svc/screening"))
                && (!result.hit() || transitions.transition(p.id(), PaymentStatus.SCREENED, payment ->
                new Change(PaymentStatus.HELD_COMPLIANCE, null, result.detail(), "svc/screening")));
    }

    /**
     * How the step after screening is entered: straight from SCREENED, or from HELD_COMPLIANCE after a
     * release, in which case the release is written in front of the history entry and its officers are
     * the actor.
     */
    record Resume(PaymentStatus from, String note, String actor) {
        static final Resume SCREENED = new Resume(PaymentStatus.SCREENED, "", null);
        static final Resume AWAITING_COVER = new Resume(PaymentStatus.AWAITING_COVER, "", null);

        String detail(String detail) {
            return note + detail;
        }

        String actor(String usual) {
            return actor != null ? actor : usual;
        }
    }

    /**
     * Held for compliance: released, the payment continues exactly as if it had passed screening;
     * rejected, the sender gets a pacs.002 RJCT RR04 ("regulatory reason") before anything is booked.
     * The RJCT says no more than that: telling the sender (and through it, the debtor) that the payment
     * hit a sanctions list could tip off the listed party, so the details stay in our case file.
     */
    private boolean afterCompliance(Payment p) {
        CaseVerdict v = screening.verdict(p);
        String actor = actor(v);
        return switch (v.verdict()) {
            case RELEASED -> act(p, new Resume(PaymentStatus.HELD_COMPLIANCE, v.summary() + " · ", actor));
            case REJECTED -> reject(p, PaymentStatus.HELD_COMPLIANCE, "RR04", v.summary(), "Regulatory reason", actor);
            case PENDING, NONE -> false;
        };
    }

    static String actor(CaseVerdict v) {
        String a = "compliance/" + v.decidedBy();
        return a.length() <= 64 ? a : a.substring(0, 64);
    }

    private boolean act(Payment p, Resume r) {
        if (p.kind() == PaymentKind.COVER) {
            return settleCoverLeg(p, r);
        }
        if (!isUs(p.creditorAgentBic())) {
            return forward(p, r);
        }
        if ("COVE".equals(p.settlementMethod())) {
            return transitions.transition(p.id(), r.from(), payment ->
                    new Change(PaymentStatus.AWAITING_COVER, null, r.detail("Holding the payment until the cover arrives via "
                            + p.coverAgentBic() + " (camt.054)"), r.actor("svc/gateway")));
        }
        return credit(p, r, inboundAccount(p).orElseThrow(), Money.of(p.interbankSettlementAmount(), p.currency()),
                "Received " + p.settlementMethod() + " from " + p.instructingAgentBic());
    }

    /**
     * Cover method, creditor's bank: the money is ours once the correspondent's camt.054 says our account
     * with it was credited. Until then nothing is booked: paying out before the cover arrives would be
     * an unsecured advance to the sender.
     */
    private boolean settleCover(Payment p) {
        Optional<MessageView> notice = messaging.forUetr(p.uetr().toString()).stream()
                .filter(m -> m.type() == MessageType.CAMT_054 && m.direction() == MessageDirection.INBOUND
                        && m.errors().isEmpty() && sameBank(m.senderBic(), p.coverAgentBic()))
                .findFirst();
        if (notice.isEmpty()) {
            return false;
        }
        CreditNotification n = messaging.creditNotification(notice.get().id());
        if (!n.amount().currencyCode().equals(p.currency())) {
            return reject(p, PaymentStatus.AWAITING_COVER, "AM03", "Cover arrived in " + n.amount().currencyCode());
        }
        return credit(p, Resume.AWAITING_COVER, inboundAccount(p).orElseThrow(), n.amount(),
                "Cover received from " + p.coverAgentBic() + " (camt.054 " + notice.get().msgId() + ")");
    }

    /** Creditor's bank: book the money in, net of our charge, to our customer; tell the sender. */
    private boolean credit(Payment p, Resume r, AccountView inbound, Money received, String how) {
        PaymentStatus from = r.from();
        AccountView creditor = ledger.findAccountByNumber(p.creditorAccount()).orElseThrow();
        Money fee = ourCharge(p, received);
        List<PostingLine> lines = new ArrayList<>();
        lines.add(new PostingLine(inbound.id(), Direction.D, received));
        lines.add(new PostingLine(creditor.id(), Direction.C, received.minus(fee)));
        feeLine(fee).ifPresent(lines::add);
        try {
            ledger.post(new PostEntryCommand(key(p, "credit"), EntryType.SETTLE, p.id(), p.executionDate(),
                    "From " + p.debtorName() + " · " + p.endToEndId(), lines));
        } catch (LedgerException e) {
            return reject(p, from, iso(e), e.getMessage());
        }
        // The confirmation states our deduction, so every bank on the route sees what the creditor received.
        MessageView acsc = status(p, TransactionStatus.ACSC, null, null,
                fee.isPositive() ? List.of(new Charge(fee, ownBic)) : List.of());
        String detail = r.detail(how) + "; credited " + received.minus(fee) + " to " + p.creditorAccount()
                + (fee.isPositive() ? " (our charge " + fee + ")" : "") + " · pacs.002 ACSC " + acsc.msgId() + " → " + p.instructingAgentBic();
        return transitions.transition(p.id(), from, payment -> {
            payment.credited(inbound.id(), fee.amount(), p.executionDate());
            network.deliver(p.id(), acsc);
            return new Change(PaymentStatus.SETTLED, null, detail, r.actor("svc/gateway"));
        });
    }

    /** Intermediary: book it through, deduct our charge, pass it on, tell the sender we accepted it. */
    private boolean forward(Payment p, Resume rs) {
        Router.Outcome outcome = router.route(p.creditorAgentBic(), p.currency(), p.executionDate());
        if (outcome.route().isEmpty()) {
            return reject(p, rs.from(), "CNOR", "No route: " + outcome.problem());
        }
        Router.Route r = outcome.route().get();
        AccountView inbound = inboundAccount(p).orElseThrow();
        Money received = Money.of(p.interbankSettlementAmount(), p.currency());
        Money fee = ourCharge(p, received);
        Money onward = received.minus(fee);
        List<PostingLine> lines = new ArrayList<>();
        lines.add(new PostingLine(inbound.id(), Direction.D, received));
        lines.add(new PostingLine(r.settlementAccountId(), Direction.C, onward));
        feeLine(fee).ifPresent(lines::add);
        try {
            ledger.post(new PostEntryCommand(key(p, "forward"), EntryType.SETTLE, p.id(), p.executionDate(),
                    "Pass-through " + p.endToEndId() + " · " + p.instructingAgentBic() + " → " + r.instructedAgentBic(), lines));
        } catch (LedgerException e) {
            return reject(p, rs.from(), iso(e), e.getMessage());
        }
        // Our own InstrId for the next hop; it must not change on a re-run, so it is derived once and
        // carried by the stored message (the business key makes the second build a replay).
        CreditTransfer in = messaging.creditTransfer(p.inboundMessageId());
        String ourInstrId = messaging.forUetr(p.uetr().toString()).stream()
                .filter(m -> m.type() == MessageType.PACS_008 && m.direction() == MessageDirection.OUTBOUND)
                .map(MessageView::instructionId).findFirst()
                .orElseGet(() -> instructionIds.next(LocalDate.now(clock.withZone(bankZone))));
        List<Charge> charges = new ArrayList<>(in.charges());
        if (fee.isPositive()) {
            charges.add(new Charge(fee, ownBic));
        }
        List<String> previous = new ArrayList<>(in.previousAgents());
        previous.add(in.instructingAgentBic());
        CreditTransfer onwardTransfer = new CreditTransfer(in.uetr(), ourInstrId, in.endToEndId(), ownBic,
                r.instructedAgentBic(), in.debtorAgentBic(), in.creditorAgentBic(), in.debtorName(), in.debtorAccount(),
                in.creditorName(), in.creditorAccount(), onward, in.instructedAmount(), charges, in.chargeBearer(),
                p.executionDate(), r.settlementMethod(), null, previous, in.remittanceInformation());
        MessageView pacs008 = messaging.issuePacs008("pacs.008:fwd:" + p.id(), onwardTransfer);
        MessageView acsp = status(p, TransactionStatus.ACSP, null, null);
        String detail = rs.note() + "Forwarded pacs.008 " + pacs008.msgId() + " → " + r.instructedAgentBic() + " · booked "
                + inbound.accountNumber() + " → " + r.settlementAccountNumber() + ", onward " + onward
                + (fee.isPositive() ? " (our charge " + fee + ")" : "") + "; routed by " + r.describe()
                + " · pacs.002 ACSP " + acsp.msgId() + " → " + p.instructingAgentBic();
        return transitions.transition(p.id(), rs.from(), payment -> {
            payment.forwarded(inbound.id(), fee.amount(), r.instructedAgentBic(), r.settlementAccountId(),
                    p.executionDate(), r.ruleId(), pacs008.instructionId(), p.settlementMethod());
            network.deliver(p.id(), pacs008);
            network.deliver(p.id(), acsp);
            return new Change(PaymentStatus.SENT, null, detail, rs.actor("svc/gateway"));
        });
    }

    /** Cover correspondent (pacs.009): debit the debtor bank, credit the creditor bank, notify it (camt.054). */
    private boolean settleCoverLeg(Payment p, Resume r) {
        AccountView from = inboundAccount(p).orElseThrow();
        AccountView to = bankAccount(AccountType.VOSTRO, p.creditorAgentBic(), p.currency()).orElseThrow();
        Money amount = Money.of(p.interbankSettlementAmount(), p.currency());
        JournalEntryView entry;
        try {
            entry = ledger.post(new PostEntryCommand(key(p, "cover"), EntryType.SETTLE, p.id(), p.executionDate(),
                    "Cover " + p.endToEndId() + " · " + p.instructingAgentBic() + " → " + p.creditorAgentBic(),
                    List.of(new PostingLine(from.id(), Direction.D, amount), new PostingLine(to.id(), Direction.C, amount)))).value();
        } catch (LedgerException e) {
            return reject(p, r.from(), iso(e), e.getMessage());
        }
        CreditTransfer underlying = messaging.coverTransfer(p.inboundMessageId()).underlying();
        MessageView camt054 = messaging.issueCamt054("camt.054:" + p.id(), new CreditNotification(ownBic,
                Bic.parse(p.creditorAgentBic()).value(), to.accountNumber(), amount, p.executionDate(), entry.createdAt(),
                p.uetr().toString(), underlying != null ? underlying.endToEndId() : p.endToEndId(), p.instructionId(),
                entry.id().toString().replace("-", "")));
        String detail = r.note() + "Debited " + from.accountNumber() + " (" + p.instructingAgentBic() + "), credited "
                + to.accountNumber() + " (" + p.creditorAgentBic() + ") " + amount + " · camt.054 " + camt054.msgId()
                + " → " + p.creditorAgentBic();
        return transitions.transition(p.id(), r.from(), payment -> {
            payment.forwarded(from.id(), BigDecimal.ZERO, Bic.parse(p.creditorAgentBic()).value(), to.id(), p.executionDate(),
                    null, null, p.settlementMethod());
            network.deliver(p.id(), camt054);
            return new Change(PaymentStatus.SETTLED, null, detail, r.actor("svc/gateway"));
        });
    }

    // ------------------------------------------------------------------ helpers

    /** Refuse before booking: REJECTED here, pacs.002 RJCT with the reason to the bank that sent it. */
    private boolean reject(Payment p, PaymentStatus from, String code, String detail) {
        return reject(p, from, code, detail, detail, "svc/validator");
    }

    /** @param told what the RJCT's AddtlInf says (may be less than {@code detail}, which stays with us) */
    private boolean reject(Payment p, PaymentStatus from, String code, String detail, String told, String actor) {
        log.info("Incoming payment {} rejected {}: {}", p.uetr(), code, detail);
        MessageView rjct = status(p, TransactionStatus.RJCT, code, told);
        return transitions.transition(p.id(), from, payment -> {
            payment.reject(code, detail);
            network.deliver(p.id(), rjct);
            return new Change(PaymentStatus.REJECTED, code, detail + " · pacs.002 RJCT " + rjct.msgId() + " → "
                    + p.instructingAgentBic(), actor);
        });
    }

    /** A pacs.002 about this payment to the bank that sent it to us (one per status, so a re-run replays it). */
    public MessageView status(Payment p, TransactionStatus status, String reason, String info) {
        return status(p, status, reason, info, List.of());
    }

    /** @param charges for an ACSC: the deductions made by the creditor's bank (us, or the bank after us) */
    public MessageView status(Payment p, TransactionStatus status, String reason, String info, List<Charge> charges) {
        String original = messaging.get(p.inboundMessageId()).message().msgId();
        return messaging.issuePacs002("pacs.002:" + status.name().toLowerCase() + ":" + p.id(), new StatusReport(ownBic,
                Bic.parse(p.instructingAgentBic()).value(), original, p.instructionId(), p.endToEndId(), p.uetr().toString(),
                status, reason, info == null ? null : info.length() > 105 ? info.substring(0, 105) : info, charges));
    }

    /** Where the money arrives, by settlement method (see the class comment). */
    Optional<AccountView> inboundAccount(Payment p) {
        return switch (p.settlementMethod()) {
            case "INDA" -> bankAccount(AccountType.VOSTRO, p.instructingAgentBic(), p.currency());
            case "INGA" -> bankAccount(AccountType.NOSTRO, p.instructingAgentBic(), p.currency());
            case "COVE" -> bankAccount(AccountType.NOSTRO, p.coverAgentBic(), p.currency());
            case "CLRG" -> rtgsAccount(p.currency(), p.executionDate());
            default -> Optional.empty();
        };
    }

    private String settlingBank(Payment p) {
        return "COVE".equals(p.settlementMethod()) ? p.coverAgentBic() : p.instructingAgentBic();
    }

    private Optional<AccountView> bankAccount(AccountType type, String bic, String currency) {
        if (bic == null || !Bic.isValid(bic)) {
            return Optional.empty();
        }
        return ledger.listAccounts(type).stream()
                .filter(a -> a.currency().equals(currency) && a.status() == AccountStatus.ACTIVE
                        && a.counterpartyBic() != null && sameBank(a.counterpartyBic(), bic))
                .findFirst();
    }

    /** Our RTGS account in a currency: the one our own routing table uses for RTGS. */
    private Optional<AccountView> rtgsAccount(String currency, LocalDate on) {
        return refdata.routingRules().stream()
                .filter(r -> r.method() == RoutingMethod.RTGS && r.currency().equals(currency) && r.validOn(on))
                .map(r -> ledger.findAccountByNumber(r.settlementAccount()))
                .flatMap(Optional::stream).findFirst();
    }

    /** Our charge on a payment that passes through us: SHAR and CRED only, and never all of it. */
    private Money ourCharge(Payment p, Money received) {
        BigDecimal configured = props.processingFees().get(p.currency());
        if (p.chargeBearer() == ChargeBearer.DEBT || configured == null || configured.signum() == 0 || feeIncome(p.currency()).isEmpty()) {
            return Money.zero(p.currency());
        }
        Money fee = Money.of(configured, CurrencyUnits.currency(p.currency()));
        return fee.isLessThan(received) ? fee : Money.zero(p.currency());
    }

    private Optional<PostingLine> feeLine(Money fee) {
        return fee.isPositive() ? Optional.of(new PostingLine(feeIncome(fee.currencyCode()).orElseThrow().id(), Direction.C, fee))
                : Optional.empty();
    }

    private Optional<AccountView> feeIncome(String currency) {
        return ledger.listAccounts(AccountType.FEE_INCOME).stream()
                .filter(a -> a.currency().equals(currency) && a.status() == AccountStatus.ACTIVE).findFirst();
    }

    private boolean isUs(String bic) {
        return sameBank(bic, ownBic);
    }

    public static boolean sameBank(String a, String b) {
        return a != null && b != null && Bic.isValid(a) && Bic.isValid(b) && Bic.parse(a).bic8().equals(Bic.parse(b).bic8());
    }

    private static String key(Payment p, String step) {
        return "pay:" + p.id() + ":" + step;
    }

    private static String iso(LedgerException e) {
        return e.error().isoReasonCode() != null ? e.error().isoReasonCode() : "NARR";
    }
}
