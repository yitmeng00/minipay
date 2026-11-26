package com.minipay.paymenthub.internal;

import com.minipay.common.id.Bic;
import com.minipay.common.id.Uetr;
import com.minipay.common.money.Money;
import com.minipay.ledger.api.AccountView;
import com.minipay.ledger.api.Direction;
import com.minipay.ledger.api.EntryType;
import com.minipay.ledger.api.JournalEntryView;
import com.minipay.ledger.api.LedgerApi;
import com.minipay.ledger.api.LedgerException;
import com.minipay.ledger.api.PostEntryCommand;
import com.minipay.ledger.api.PostingLine;
import com.minipay.ledger.api.ReverseEntryCommand;
import com.minipay.messaging.api.Charge;
import com.minipay.messaging.api.CoverTransfer;
import com.minipay.messaging.api.CreditTransfer;
import com.minipay.messaging.api.InboundMessage;
import com.minipay.messaging.api.MessageDirection;
import com.minipay.messaging.api.MessageType;
import com.minipay.messaging.api.MessageView;
import com.minipay.messaging.api.MessagingApi;
import com.minipay.messaging.api.PaymentReturn;
import com.minipay.messaging.api.StatusReport;
import com.minipay.messaging.api.TransactionStatus;
import com.minipay.paymenthub.api.CreatePaymentCommand;
import com.minipay.paymenthub.api.DailyOverview;
import com.minipay.paymenthub.api.NetworkEvent;
import com.minipay.paymenthub.api.PageView;
import com.minipay.paymenthub.api.PaymentDetailView;
import com.minipay.paymenthub.api.PaymentDirection;
import com.minipay.paymenthub.api.PaymentError;
import com.minipay.paymenthub.api.PaymentException;
import com.minipay.paymenthub.api.PaymentHubApi;
import com.minipay.paymenthub.api.PaymentKind;
import com.minipay.paymenthub.api.PaymentPreview;
import com.minipay.paymenthub.api.PaymentQuery;
import com.minipay.paymenthub.api.PaymentStatus;
import com.minipay.paymenthub.api.PaymentView;
import com.minipay.paymenthub.api.Replayable;
import com.minipay.paymenthub.internal.config.PaymentHubProperties;
import com.minipay.paymenthub.internal.domain.IdempotencyRecord;
import com.minipay.paymenthub.internal.domain.Payment;
import com.minipay.paymenthub.internal.domain.PaymentStateMachine;
import com.minipay.paymenthub.internal.network.NetworkGateway;
import com.minipay.paymenthub.internal.persistence.IdempotencyRecordRepository;
import com.minipay.paymenthub.internal.persistence.PaymentRepository;
import com.minipay.paymenthub.internal.persistence.PaymentSpecifications;
import com.minipay.paymenthub.internal.persistence.PaymentStateHistoryRepository;
import com.minipay.paymenthub.internal.persistence.PaymentStatistics;
import com.minipay.paymenthub.internal.processing.IncomingProcessor;
import com.minipay.paymenthub.internal.processing.PaymentMapper;
import com.minipay.paymenthub.internal.processing.PaymentProcessor;
import com.minipay.paymenthub.internal.processing.PaymentTransitions.Change;
import com.minipay.paymenthub.internal.processing.PaymentTransitions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService implements PaymentHubApi {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{1,100}$");
    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

    private final PaymentRepository payments;
    private final PaymentStateHistoryRepository history;
    private final IdempotencyRecordRepository idempotency;
    private final PaymentTransitions transitions;
    private final PaymentProcessor processor;
    private final LedgerApi ledger;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String bankBic;
    private final PaymentStatistics statistics;
    private final PaymentHubProperties props;
    private final ZoneId bankZone;
    private final MessagingApi messaging;
    private final NetworkGateway network;
    private final IncomingProcessor incoming;

    public PaymentService(PaymentRepository payments, PaymentStateHistoryRepository history,
                          IdempotencyRecordRepository idempotency, PaymentTransitions transitions,
                          PaymentProcessor processor, LedgerApi ledger, PlatformTransactionManager txManager,
                          ObjectProvider<Clock> clock, @Value("${minipay.bank.bic}") String bankBic,
                          PaymentStatistics statistics, PaymentHubProperties props,
                          @Value("${minipay.bank.time-zone:UTC}") ZoneId bankZone, MessagingApi messaging,
                          NetworkGateway network, IncomingProcessor incoming) {
        this.messaging = messaging;
        this.network = network;
        this.incoming = incoming;
        this.statistics = statistics;
        this.props = props;
        this.bankZone = bankZone;
        this.payments = payments;
        this.history = history;
        this.idempotency = idempotency;
        this.transitions = transitions;
        this.processor = processor;
        this.ledger = ledger;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock.getIfAvailable(Clock::systemUTC);
        this.bankBic = bankBic;
    }

    // =================================================================== create

    /**
     * Step 1 (one transaction): store the payment as RECEIVED with its history row, outbox event and
     * idempotency record. Step 2: run the pipeline. If step 2 is interrupted, the payment is safely
     * stored and the recovery sweeper finishes the job.
     */
    @Override
    public Replayable<PaymentView> create(CreatePaymentCommand c) {
        String creditorAccount = c.creditorAccount() == null ? null
                : c.creditorAccount().replace(" ", "").toUpperCase(Locale.ROOT);
        String creditorAgentBic = c.creditorAgentBic() == null ? null : c.creditorAgentBic().trim().toUpperCase(Locale.ROOT);
        // NORM is left out of the fingerprint, so keys stored before service levels existed still replay.
        String hash = c.serviceLevel() == com.minipay.paymenthub.api.ServiceLevel.NORM
                ? fingerprint("create", c.debtorAccount(), c.creditorName(), creditorAccount, creditorAgentBic,
                c.amount(), c.chargeBearer(), c.requestedExecutionDate(), c.endToEndId(), c.remittanceInformation())
                : fingerprint("create", c.debtorAccount(), c.creditorName(), creditorAccount, creditorAgentBic,
                c.amount(), c.chargeBearer(), c.requestedExecutionDate(), c.endToEndId(), c.remittanceInformation(),
                c.serviceLevel());

        Optional<Replayable<PaymentView>> replay = replay(c.idempotencyKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        UUID id;
        try {
            id = tx.execute(s -> {
                String debtorName = ledger.findAccountByNumber(c.debtorAccount()).map(AccountView::ownerName).orElse("UNKNOWN");
                Payment p = Payment.receiveOutgoing(
                        UUID.fromString(Uetr.generate().value()),
                        c.endToEndId() == null || c.endToEndId().isBlank() ? "NOTPROVIDED" : c.endToEndId(),
                        instructionId(), c.amount().amount(), c.amount().currencyCode(), c.chargeBearer(), debtorName,
                        c.debtorAccount(), bankBic, c.creditorName(), creditorAccount, creditorAgentBic,
                        c.remittanceInformation(),
                        c.requestedExecutionDate() != null ? c.requestedExecutionDate() : processor.businessDate(),
                        clock.instant());
                p.serviceLevel(c.serviceLevel());
                payments.saveAndFlush(p);
                transitions.recordReceived(p, "Accepted from channel REST API"
                        + (c.serviceLevel() == com.minipay.paymenthub.api.ServiceLevel.NORM ? "" : " (" + c.serviceLevel() + ")"),
                        "api/payments");
                idempotency.saveAndFlush(new IdempotencyRecord(c.idempotencyKey(), hash, "create", p.id(), clock.instant()));
                return p.id();
            });
        } catch (DataIntegrityViolationException e) {
            // Same key sent twice at the same moment: the other request won the insert. Replay it.
            return replay(c.idempotencyKey(), hash).orElseThrow(() -> e);
        }
        runPipeline(id);
        return new Replayable<>(PaymentMapper.toView(payments.findById(id).orElseThrow()), false);
    }

    private void runPipeline(UUID id) {
        try {
            processor.process(id);
        } catch (RuntimeException e) {
            // The payment is stored; the recovery sweeper will resume it. Do not fail the request.
            log.warn("Processing of payment {} interrupted, will be resumed: {}", id, e.toString());
        }
    }

    /**
     * "<BIC4><yyMMdd><6-digit sequence>", e.g. MNPA261124000042: unique per sending bank and day, and
     * 16 characters. CBPR+ limits InstrId to 16 during the MT/MX coexistence period, so it still fits
     * MT103 field :20: when a bank on the route translates the payment.
     */
    private String instructionId() {
        return bankBic.substring(0, 4) + processor.businessDate().format(YYMMDD)
                + String.format("%06d", payments.nextInstructionSeq() % 1_000_000);
    }

    // =================================================================== network events

    /**
     * SIMULATION for banks outside the simulator: plays the next bank and sends us the message it would
     * send (pacs.002 or pacs.004), which is then received like any real message. The three simulated
     * banks answer for themselves over the network, so this is refused for them.
     */
    @Override
    public Replayable<PaymentView> onNetworkEvent(String key, String uetr, NetworkEvent event) {
        String hash = fingerprint("network-event", uetr, event.type(), event.reasonCode(), event.returnedAmount(),
                event.reference());
        Optional<Replayable<PaymentView>> replay = replay(key, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Payment p = find(uetr);
        if (p.instructedAgentBic() == null) {
            throw new PaymentException(PaymentError.ILLEGAL_TRANSITION,
                    "Payment " + uetr + " has not been sent (" + p.status() + "): no bank can answer it yet");
        }
        if (network.isParticipant(p.instructedAgentBic())) {
            throw new PaymentException(PaymentError.ILLEGAL_TRANSITION, p.instructedAgentBic()
                    + " is one of the simulated banks: it answers over the network itself");
        }
        PaymentStatus target = targetOf(event.type());
        PaymentStateMachine.check(p.direction(), p.status(), target); // fail fast, before building anything
        String originalMsgId = ourPacs008(p).map(MessageView::msgId).orElse(null);
        String raw = switch (event.type()) {
            case ACCEPTED, SETTLED, REJECTED -> messaging.buildPacs002(new StatusReport(p.instructedAgentBic(), bankBic,
                    originalMsgId, p.instructionId(), p.endToEndId(), p.uetr().toString(),
                    switch (event.type()) {
                        case ACCEPTED -> TransactionStatus.ACCP;
                        case SETTLED -> TransactionStatus.ACSC;
                        default -> TransactionStatus.RJCT;
                    }, event.reasonCode(), event.reference()));
            case RETURNED -> {
                Money sent = Money.of(p.interbankSettlementAmount(), p.currency());
                Money returned = event.returnedAmount() != null ? event.returnedAmount() : sent;
                checkReturnedAmount(p, returned);
                Money kept = sent.minus(returned);
                yield messaging.buildPacs004(new PaymentReturn(p.instructedAgentBic(), bankBic,
                        "RTN-" + p.instructionId(), originalMsgId, p.instructionId(), p.endToEndId(), p.uetr().toString(),
                        sent, returned, processor.businessDate(),
                        kept.isPositive() ? List.of(new Charge(kept, p.instructedAgentBic())) : List.of(),
                        event.reasonCode(), event.reference()));
            }
        };
        return receive(key, hash, raw, " (simulated)");
    }

    /** A message from another bank: a payment, a cover, a credit notification, a status or a return. */
    @Override
    public Replayable<PaymentView> receiveMessage(String key, String raw) {
        String hash = fingerprint("inbound-message", raw);
        return replay(key, hash).orElseGet(() -> receive(key, hash, raw, ""));
    }

    private Replayable<PaymentView> receive(String key, String hash, String raw, String note) {
        InboundMessage in = messaging.receive(raw); // stored first, whatever happens next (rule 8)
        MessageView m = in.message();
        if (!in.valid()) {
            throw new PaymentException(PaymentError.INVALID_MESSAGE,
                    "Message stored as INVALID (" + m.id() + "): " + String.join("; ", m.errors()));
        }
        return switch (m.type()) {
            case PACS_008, PACS_009 -> receivePayment(key, hash, in);
            case CAMT_054 -> receiveCreditNotification(in);
            case PACS_002, PACS_004 -> receiveOutcome(key, hash, in, note);
            default -> throw new PaymentException(PaymentError.UNSUPPORTED_MESSAGE,
                    m.type().identifier() + " stored (" + m.id() + "); the hub does not act on it");
        };
    }

    /** A payment sent to us (pacs.008), or the money leg of a cover payment for us to settle (pacs.009). */
    private Replayable<PaymentView> receivePayment(String key, String hash, InboundMessage in) {
        MessageView m = in.message();
        UUID uetr = UUID.fromString(in.originalUetr());
        Optional<Payment> known = payments.findByUetr(uetr);
        if (known.isPresent()) {
            if (in.duplicate() || m.id().equals(known.get().inboundMessageId())) {
                return new Replayable<>(PaymentMapper.toView(known.get()), true);
            }
            throw new PaymentException(PaymentError.INVALID_MESSAGE, "UETR " + uetr + " is already payment "
                    + known.get().id() + ": a second payment under it is a duplicate (DUPL); message stored (" + m.id() + ")");
        }
        Payment p = m.type() == MessageType.PACS_008 ? fromPacs008(m) : fromPacs009(m);
        String detail = m.type().identifier().substring(0, 8) + " " + m.msgId() + " from " + m.senderBic();
        UUID id;
        try {
            id = tx.execute(s -> {
                payments.saveAndFlush(p);
                transitions.recordReceived(p, detail, "net/" + m.senderBic());
                idempotency.saveAndFlush(new IdempotencyRecord(key, hash, "inbound", p.id(), clock.instant()));
                return p.id();
            });
        } catch (DataIntegrityViolationException e) {
            // A redelivery raced us: the other attempt stored the payment. Replay it.
            return replay(key, hash).or(() -> payments.findByUetr(uetr).map(x -> new Replayable<>(PaymentMapper.toView(x), true)))
                    .orElseThrow(() -> e);
        }
        runPipeline(id);
        return new Replayable<>(PaymentMapper.toView(payments.findById(id).orElseThrow()), false);
    }

    private Payment fromPacs008(MessageView m) {
        CreditTransfer t = messaging.creditTransfer(m.id());
        Money instructed = t.instructedAmount() != null ? t.instructedAmount() : t.interbankAmount();
        Payment p = receiveIncoming(m, t, instructed);
        // Forwarded by a clearing system (ADR-008): its operator sent it to us (AppHdr/Fr), the payer bank stays InstgAgt.
        if (t.clearing() != null && t.clearing().system() != null && !IncomingProcessor.sameBank(m.senderBic(), t.instructingAgentBic())) {
            p.throughSystem(t.clearing().system(), Bic.parse(m.senderBic()).value());
            p.serviceLevel(t.clearing().instant() ? com.minipay.paymenthub.api.ServiceLevel.INST
                    : "HIGH".equals(t.clearing().priority()) ? com.minipay.paymenthub.api.ServiceLevel.HIGH
                    : com.minipay.paymenthub.api.ServiceLevel.NORM);
        }
        return p;
    }

    private Payment receiveIncoming(MessageView m, CreditTransfer t, Money instructed) {
        return Payment.receiveIncoming(PaymentKind.CUSTOMER, UUID.fromString(t.uetr()), t.endToEndId(), t.instructionId(),
                instructed.amount(), t.interbankAmount().amount(), t.interbankAmount().currencyCode(), t.chargeBearer(),
                t.debtorName(), t.debtorAccount(), t.debtorAgentBic(), t.creditorName(), t.creditorAccount(),
                t.creditorAgentBic(), t.remittanceInformation(), t.settlementDate(), Bic.parse(t.instructingAgentBic()).value(),
                t.settlementMethod().name(), t.coverAgentBic(), m.id(), clock.instant());
    }

    private Payment fromPacs009(MessageView m) {
        CoverTransfer c = messaging.coverTransfer(m.id());
        CreditTransfer u = c.underlying();
        return Payment.receiveIncoming(PaymentKind.COVER, UUID.fromString(c.uetr()), c.endToEndId(), c.instructionId(),
                c.amount().amount(), c.amount().amount(), c.amount().currencyCode(), u.chargeBearer(),
                u.debtorName(), u.debtorAccount(), Bic.parse(c.debtorBic()).value(), u.creditorName(), u.creditorAccount(),
                Bic.parse(c.creditorBic()).value(), u.remittanceInformation(), c.settlementDate(),
                Bic.parse(c.instructingAgentBic()).value(), "INDA", null, m.id(), clock.instant());
    }

    /**
     * camt.054 from a cover agent: the money of a cover payment has arrived. If we already hold the
     * pacs.008 it is paid out now; if the notification came first, it waits in the store and is matched
     * when the pacs.008 arrives (the network does not guarantee order).
     */
    private Replayable<PaymentView> receiveCreditNotification(InboundMessage in) {
        Payment p = payments.findByUetr(UUID.fromString(in.originalUetr())).orElseThrow(() ->
                new PaymentException(PaymentError.PAYMENT_NOT_FOUND, "camt.054 stored (" + in.message().id()
                        + "); no payment with UETR " + in.originalUetr() + " yet: it is matched when the pacs.008 arrives"));
        if (p.status() == PaymentStatus.AWAITING_COVER) {
            runPipeline(p.id());
        }
        return new Replayable<>(PaymentMapper.toView(payments.findById(p.id()).orElseThrow()), in.duplicate());
    }

    /** A status (pacs.002) or a return (pacs.004) about a payment we sent or passed on. */
    private Replayable<PaymentView> receiveOutcome(String key, String hash, InboundMessage in, String note) {
        MessageView m = in.message();
        Payment p = find(in.originalUetr());
        boolean fromNextBank = p.instructedAgentBic() != null && IncomingProcessor.sameBank(m.senderBic(), p.instructedAgentBic());
        boolean fromCoverAgent = IncomingProcessor.sameBank(m.senderBic(), p.coverAgentBic());
        boolean fromSystem = IncomingProcessor.sameBank(m.senderBic(), p.viaBic());
        if (!fromNextBank && !fromCoverAgent && !fromSystem) {
            throw new PaymentException(PaymentError.INVALID_NETWORK_EVENT, m.type().identifier() + " from " + m.senderBic()
                    + ", but payment " + p.uetr() + " was sent to " + p.instructedAgentBic());
        }
        NetworkEvent event = switch (m.type()) {
            case PACS_004 -> new NetworkEvent(NetworkEvent.Type.RETURNED, in.reasonCode(), in.returnedAmount(), in.additionalInfo());
            default -> switch (in.status()) {
                case "ACCP", "ACSP" -> new NetworkEvent(NetworkEvent.Type.ACCEPTED, null, null, null);
                case "ACSC" -> new NetworkEvent(NetworkEvent.Type.SETTLED, null, null, null);
                default -> new NetworkEvent(NetworkEvent.Type.REJECTED, in.reasonCode(), null, in.additionalInfo());
            };
        };
        PaymentStatus target = targetOf(event.type());
        // A duplicate, or a status that arrives after a later one (an ACCP after the ACSC), must not move the
        // payment backwards. It is stored, and acknowledged, and nothing else happens.
        if (in.duplicate() || alreadyPast(p.status(), target)) {
            return new Replayable<>(PaymentMapper.toView(p), true);
        }
        // An instant payment to us: the scheme's ACSC lets us credit the customer; its RJCT (a timeout) ends it.
        if (p.direction() == PaymentDirection.INCOMING && fromSystem && m.type() == MessageType.PACS_002
                && p.serviceLevel() == com.minipay.paymenthub.api.ServiceLevel.INST) {
            String ref = "pacs.002 " + m.msgId() + " " + in.status() + " from " + m.senderBic();
            incoming.instantOutcome(p, event.type() == NetworkEvent.Type.SETTLED, event.reasonCode(), event.reference(), ref);
            return new Replayable<>(PaymentMapper.toView(payments.findById(p.id()).orElseThrow()), false);
        }
        String ref = m.type().identifier().substring(0, 8) + " " + m.msgId()
                + (m.type() == MessageType.PACS_002 ? " " + in.status() : "") + note;
        return apply(key, hash, p, event, "net/" + m.senderBic(), ref, m);
    }

    private static PaymentStatus targetOf(NetworkEvent.Type type) {
        return switch (type) {
            case ACCEPTED -> PaymentStatus.ACKED;
            case SETTLED -> PaymentStatus.SETTLED;
            case REJECTED -> PaymentStatus.REJECTED;
            case RETURNED -> PaymentStatus.RETURNED;
        };
    }

    /** A report for an earlier stage, or about a payment that is already final, changes nothing. */
    private static boolean alreadyPast(PaymentStatus current, PaymentStatus target) {
        if (current.isFinal()) {
            return true;
        }
        List<PaymentStatus> order = List.of(PaymentStatus.SENT, PaymentStatus.ACKED, PaymentStatus.SETTLED);
        int c = order.indexOf(current);
        int t = order.indexOf(target);
        return c >= 0 && t >= 0 && c >= t;
    }

    /**
     * Applies what the next bank told us. Its effects on the ledger come first (each with a key derived
     * from the payment, so a retry replays), then one transaction records the status and puts any message
     * for the bank before us on the network. For a payment we passed on, every outcome travels back one
     * hop: the creditor's ACSC, a rejection, or a return reaches the debtor's bank through every bank in
     * between.
     */
    private Replayable<PaymentView> apply(String key, String hash, Payment p, NetworkEvent event, String actor, String ref,
                                          MessageView inbound) {
        PaymentStatus target = targetOf(event.type());
        PaymentStateMachine.check(p.direction(), p.status(), target); // fail fast, before touching the ledger
        boolean passedOn = p.direction() == PaymentDirection.INCOMING;
        MessageView upstream = null;
        String detail;
        switch (event.type()) {
            case ACCEPTED -> detail = "Accepted by " + p.instructedAgentBic() + " · " + ref;
            case SETTLED -> {
                if (passedOn) {
                    // Pass the creditor's confirmation back, with the deductions it reported.
                    upstream = incoming.status(p, TransactionStatus.ACSC, null, null,
                            messaging.inspect(messaging.get(inbound.id()).raw()).charges());
                }
                detail = (IncomingProcessor.sameBank(inbound.senderBic(), p.viaBic())
                        ? "Settled by " + p.clearingSystem() + " (final)" : "Credited by the creditor's bank") + " · " + ref
                        + (upstream != null ? " · ACSC " + upstream.msgId() + " → " + p.instructingAgentBic() : "");
            }
            case REJECTED -> {
                List<String> reversed = reverseBookings(p, event.reasonCode());
                if (passedOn) {
                    upstream = incoming.status(p, TransactionStatus.RJCT, event.reasonCode(), event.reference());
                }
                detail = "Rejected " + event.reasonCode() + " · " + ref + " · reversed " + String.join(", ", reversed)
                        + (upstream != null ? " · RJCT " + upstream.msgId() + " → " + p.instructingAgentBic() : "");
            }
            case RETURNED -> {
                Money returned;
                if (passedOn) {
                    returned = bookReturnThrough(p, event);
                    upstream = returnUpstream(p, inbound, returned);
                } else {
                    returned = bookReturn(p, event);
                }
                detail = "Return " + event.reasonCode() + " · " + returned
                        + (passedOn ? " passed back to " + p.instructingAgentBic() + " (pacs.004 " + upstream.msgId() + ")"
                        : " credited to " + p.debtorAccount()) + " · " + ref;
            }
            default -> throw new IllegalStateException();
        }
        MessageView toDeliver = upstream;
        String finalDetail = detail;
        try {
            boolean done = tx.execute(s -> {
                boolean moved = transitions.transition(p.id(), p.status(), payment -> {
                    if (event.type() == NetworkEvent.Type.RETURNED || event.type() == NetworkEvent.Type.REJECTED) {
                        payment.reject(event.reasonCode(), (event.type() == NetworkEvent.Type.RETURNED ? "Returned" : "Rejected")
                                + " by the network: " + event.reasonCode());
                    }
                    if (toDeliver != null) {
                        network.deliver(p.id(), toDeliver);
                    }
                    return new Change(target, event.type() == NetworkEvent.Type.REJECTED || event.type() == NetworkEvent.Type.RETURNED
                            ? event.reasonCode() : null, finalDetail, actor);
                });
                if (moved) {
                    idempotency.saveAndFlush(new IdempotencyRecord(key, hash, "network-event", p.id(), clock.instant()));
                }
                return moved;
            });
            if (!done) {
                throw new PaymentException(PaymentError.ILLEGAL_TRANSITION,
                        "Payment " + p.uetr() + " changed concurrently; retry the request");
            }
        } catch (DataIntegrityViolationException e) {
            return replay(key, hash).orElseThrow(() -> e);
        }
        return new Replayable<>(PaymentMapper.toView(payments.findById(p.id()).orElseThrow()), false);
    }

    /**
     * A bank further on rejected the payment: what we booked when we sent it never happened, so it is
     * reversed (a REVERSAL entry per booking, never a deletion). Our customer gets back the principal and
     * our fee; a bank that passed it on gets the sender's account back to where it was.
     */
    private List<String> reverseBookings(Payment p, String reason) {
        List<String> reversed = new java.util.ArrayList<>();
        java.util.Set<UUID> alreadyReversed = ledger.entriesForPayment(p.id()).stream()
                .map(JournalEntryView::reversesEntryId).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        for (JournalEntryView e : ledger.entriesForPayment(p.id())) {
            if ((e.entryType() == EntryType.SETTLE || e.entryType() == EntryType.FEE) && e.reversesEntryId() == null) {
                if (!alreadyReversed.contains(e.id())) {
                    ledger.reverse(new ReverseEntryCommand("pay:" + p.id() + ":reverse:" + e.id(), e.id(),
                            "Rejected " + reason + " by the next bank"));
                }
                reversed.add(e.entryType().name());
            }
        }
        return reversed;
    }

    /** We passed it on and it came back: the next bank's account is debited, the sender's credited. */
    private Money bookReturnThrough(Payment p, NetworkEvent event) {
        Money returned = event.returnedAmount();
        ledger.post(new PostEntryCommand("pay:" + p.id() + ":return", EntryType.SETTLE, p.id(), null,
                "Return " + event.reasonCode() + " · " + p.endToEndId() + " passed back to " + p.instructingAgentBic(),
                List.of(new PostingLine(p.settlementAccountId(), Direction.D, returned),
                        new PostingLine(p.inboundAccountId(), Direction.C, returned))));
        return returned;
    }

    /** The return travels on to the bank that sent us the payment, with every bank's deduction listed. */
    private MessageView returnUpstream(Payment p, MessageView inbound, Money returned) {
        PaymentReturn ret = messaging.paymentReturn(inbound.id());
        List<Charge> charges = new java.util.ArrayList<>(ret.charges());
        Money ours = Money.of(p.chargesAmount(), p.currency());
        if (ours.isPositive() && charges.stream().noneMatch(c -> IncomingProcessor.sameBank(c.agentBic(), bankBic))) {
            charges.add(new Charge(ours, bankBic));
        }
        String original = messaging.get(p.inboundMessageId()).message().msgId();
        return messaging.issuePacs004("pacs.004:" + p.id(), new PaymentReturn(bankBic, Bic.parse(p.instructingAgentBic()).value(),
                "RTN-" + (p.forwardInstructionId() != null ? p.forwardInstructionId() : p.instructionId()), original,
                p.instructionId(), p.endToEndId(), p.uetr().toString(), Money.of(p.interbankSettlementAmount(), p.currency()),
                returned, processor.businessDate(), charges, ret.reasonCode(), ret.additionalInfo()));
    }

    /**
     * As the creditor's bank, send the money back (pacs.004), e.g. because the creditor's account turned
     * out to be closed. We return what we credited: our charge, and those of the banks before us, are
     * listed in ChrgsInf, so the debtor's bank sees exactly why less comes back than it sent.
     */
    @Override
    public Replayable<PaymentView> returnPayment(String key, String uetr, String reasonCode, String additionalInfo) {
        String hash = fingerprint("return", uetr, reasonCode, additionalInfo);
        Optional<Replayable<PaymentView>> replay = replay(key, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (reasonCode == null || !reasonCode.matches("[A-Z0-9]{4}")) {
            throw new IllegalArgumentException("A return needs a 4-character ISO reason code, e.g. AC04");
        }
        Payment p = find(uetr);
        if (p.direction() != PaymentDirection.INCOMING || p.kind() != PaymentKind.CUSTOMER || p.instructedAgentBic() != null) {
            throw new PaymentException(PaymentError.ILLEGAL_TRANSITION, "Only a payment we credited to our own customer can be returned by us");
        }
        if ("COVE".equals(p.settlementMethod())) {
            throw new PaymentException(PaymentError.ILLEGAL_TRANSITION, "Returning a cover payment is not modelled");
        }
        PaymentStateMachine.check(p.direction(), p.status(), PaymentStatus.RETURNED);
        Money credited = Money.of(p.interbankSettlementAmount(), p.currency()).minus(Money.of(p.chargesAmount(), p.currency()));
        AccountView creditor = ledger.findAccountByNumber(p.creditorAccount()).orElseThrow();
        try {
            ledger.post(new PostEntryCommand("pay:" + p.id() + ":return-out", EntryType.SETTLE, p.id(), null,
                    "Return " + reasonCode + " · " + p.endToEndId() + " to " + p.instructingAgentBic(),
                    List.of(new PostingLine(creditor.id(), Direction.D, credited), new PostingLine(p.inboundAccountId(), Direction.C, credited))));
        } catch (LedgerException e) {
            throw new PaymentException(PaymentError.INVALID_NETWORK_EVENT, "Cannot return: " + e.getMessage());
        }
        List<Charge> charges = new java.util.ArrayList<>(messaging.creditTransfer(p.inboundMessageId()).charges());
        if (p.chargesAmount().signum() > 0) {
            charges.add(new Charge(Money.of(p.chargesAmount(), p.currency()), bankBic));
        }
        String original = messaging.get(p.inboundMessageId()).message().msgId();
        // Received through a clearing system, the money goes back through it too (ADR-008).
        String returnTo = p.viaBic() != null ? p.viaBic() : Bic.parse(p.instructingAgentBic()).value();
        MessageView pacs004 = messaging.issuePacs004("pacs.004:" + p.id(), new PaymentReturn(bankBic,
                returnTo, "RTN-" + p.instructionId(), original, p.instructionId(),
                p.endToEndId(), p.uetr().toString(), Money.of(p.interbankSettlementAmount(), p.currency()), credited,
                processor.businessDate(), charges, reasonCode, additionalInfo));
        tx.executeWithoutResult(s -> {
            boolean moved = transitions.transition(p.id(), PaymentStatus.SETTLED, payment -> {
                payment.reject(reasonCode, "Returned by us: " + reasonCode);
                network.deliver(p.id(), pacs004);
                return new Change(PaymentStatus.RETURNED, reasonCode, "Returned " + credited + " to " + p.instructingAgentBic()
                        + " · pacs.004 " + pacs004.msgId() + (additionalInfo != null ? " · " + additionalInfo : ""), "ops/console");
            });
            if (moved) {
                idempotency.saveAndFlush(new IdempotencyRecord(key, hash, "return", p.id(), clock.instant()));
            }
        });
        return new Replayable<>(PaymentMapper.toView(payments.findById(p.id()).orElseThrow()), false);
    }

    private Optional<MessageView> ourPacs008(Payment p) {
        return messaging.forUetr(p.uetr().toString()).stream()
                .filter(m -> m.type() == MessageType.PACS_008 && m.direction() == MessageDirection.OUTBOUND)
                .findFirst();
    }

    private static void checkReturnedAmount(Payment p, Money returned) {
        Money sent = Money.of(p.interbankSettlementAmount(), p.currency());
        if (!returned.currencyCode().equals(p.currency()) || !returned.isPositive() || returned.isGreaterThan(sent)) {
            throw new PaymentException(PaymentError.INVALID_NETWORK_EVENT,
                    "Returned amount must be positive, in " + p.currency() + " and at most " + sent);
        }
    }

    @Override
    public PaymentPreview preview(CreatePaymentCommand c) {
        String creditorAccount = c.creditorAccount() == null ? "" : c.creditorAccount().replace(" ", "").toUpperCase(Locale.ROOT);
        String agent = c.creditorAgentBic() == null ? "" : c.creditorAgentBic().trim().toUpperCase(Locale.ROOT);
        PaymentProcessor.Preview pv = processor.preview(c.debtorAccount(), null, c.creditorName(), creditorAccount, agent,
                c.amount(), c.chargeBearer(), c.requestedExecutionDate(), c.endToEndId(), c.remittanceInformation(),
                c.serviceLevel());
        return new PaymentPreview(pv.xml(), pv.notes());
    }

    /**
     * A pacs.004 return of a payment we sent: the money comes back to our settlement account (nostro/vostro)
     * and we credit the debtor. The returned amount may be less than sent — banks on the route deducted
     * charges — and our own fee is not refunded.
     */
    private Money bookReturn(Payment p, NetworkEvent event) {
        Money sent = Money.of(p.interbankSettlementAmount(), p.currency());
        Money returned = event.returnedAmount() != null ? event.returnedAmount() : sent;
        checkReturnedAmount(p, returned);
        AccountView debtor = ledger.findAccountByNumber(p.debtorAccount()).orElseThrow();
        ledger.post(new PostEntryCommand("pay:" + p.id() + ":return", EntryType.SETTLE, p.id(), null,
                "Return " + event.reasonCode() + " · " + p.endToEndId(),
                List.of(new PostingLine(p.settlementAccountId(), Direction.D, returned),
                        new PostingLine(debtor.id(), Direction.C, returned))));
        return returned;
    }

    // =================================================================== queries

    @Override
    public PaymentDetailView get(String uetr) {
        Payment p = find(uetr);
        return new PaymentDetailView(PaymentMapper.toView(p),
                history.findByPaymentIdOrderByIdAsc(p.id()).stream().map(PaymentMapper::toView).toList());
    }

    @Override
    public PageView<PaymentView> search(PaymentQuery q) {
        var page = payments.findAll(PaymentSpecifications.matching(q),
                PageRequest.of(q.page(), q.size(), Sort.by(Sort.Direction.DESC, "createdAt")));
        return new PageView<>(page.map(PaymentMapper::toView).getContent(), q.page(), q.size(), page.getTotalElements());
    }

    @Override
    public Map<PaymentStatus, Long> countByStatus() {
        Map<PaymentStatus, Long> counts = new EnumMap<>(PaymentStatus.class);
        for (PaymentStatus s : PaymentStatus.values()) {
            counts.put(s, 0L);
        }
        for (Object[] row : payments.countGroupedByStatus()) {
            counts.put((PaymentStatus) row[0], (Long) row[1]);
        }
        return counts;
    }

    @Override
    @Transactional(readOnly = true)
    public DailyOverview overview(LocalDate date) {
        return statistics.overview(date != null ? date : processor.businessDate(), bankZone, clock.instant(),
                props.sentStuckAfter());
    }

    // =================================================================== helpers

    /**
     * Finds a payment by UETR — or by the hub's internal payment id, which is what the ledger stores
     * on journal entries (so a statement line can link to its payment).
     */
    private Payment find(String ref) {
        if (!Uetr.isValid(ref == null ? null : ref.toLowerCase(Locale.ROOT))) {
            throw new PaymentException(PaymentError.PAYMENT_NOT_FOUND, "Not a valid UETR: " + ref);
        }
        UUID id = UUID.fromString(ref);
        return payments.findByUetr(id).or(() -> payments.findById(id)).orElseThrow(() ->
                new PaymentException(PaymentError.PAYMENT_NOT_FOUND, "No payment with UETR " + ref));
    }

    private Optional<Replayable<PaymentView>> replay(String key, String hash) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Idempotency-Key must be 1-100 chars of A-Z a-z 0-9 . _ : -");
        }
        return idempotency.findById(key).map(r -> {
            if (!r.requestHash().equals(hash)) {
                throw new PaymentException(PaymentError.IDEMPOTENCY_KEY_REUSED,
                        "Idempotency-Key '" + key + "' was already used for a different request");
            }
            return new Replayable<>(PaymentMapper.toView(payments.findById(r.paymentId()).orElseThrow()), true);
        });
    }

    /** SHA-256 over the parsed request (so "10" and "10.00", or key order, do not matter). */
    private static String fingerprint(String op, Object... fields) {
        StringBuilder sb = new StringBuilder(op);
        for (Object f : fields) {
            sb.append('\u001F').append(f == null ? "∅" : f.toString());
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
