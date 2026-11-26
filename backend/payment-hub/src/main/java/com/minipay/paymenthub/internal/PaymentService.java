package com.minipay.paymenthub.internal;

import com.minipay.common.id.Bic;
import com.minipay.common.id.Uetr;
import com.minipay.common.money.Money;
import com.minipay.ledger.api.AccountView;
import com.minipay.ledger.api.Direction;
import com.minipay.ledger.api.EntryType;
import com.minipay.ledger.api.LedgerApi;
import com.minipay.ledger.api.PostEntryCommand;
import com.minipay.ledger.api.PostingLine;
import com.minipay.messaging.api.Charge;
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
import com.minipay.paymenthub.api.PaymentError;
import com.minipay.paymenthub.api.PaymentException;
import com.minipay.paymenthub.api.PaymentHubApi;
import com.minipay.paymenthub.api.PaymentPreview;
import com.minipay.paymenthub.api.PaymentQuery;
import com.minipay.paymenthub.api.PaymentStatus;
import com.minipay.paymenthub.api.PaymentView;
import com.minipay.paymenthub.api.Replayable;
import com.minipay.paymenthub.internal.config.PaymentHubProperties;
import com.minipay.paymenthub.internal.domain.IdempotencyRecord;
import com.minipay.paymenthub.internal.domain.Payment;
import com.minipay.paymenthub.internal.domain.PaymentStateMachine;
import com.minipay.paymenthub.internal.persistence.IdempotencyRecordRepository;
import com.minipay.paymenthub.internal.persistence.PaymentRepository;
import com.minipay.paymenthub.internal.persistence.PaymentSpecifications;
import com.minipay.paymenthub.internal.persistence.PaymentStateHistoryRepository;
import com.minipay.paymenthub.internal.persistence.PaymentStatistics;
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

    public PaymentService(PaymentRepository payments, PaymentStateHistoryRepository history,
                          IdempotencyRecordRepository idempotency, PaymentTransitions transitions,
                          PaymentProcessor processor, LedgerApi ledger, PlatformTransactionManager txManager,
                          ObjectProvider<Clock> clock, @Value("${minipay.bank.bic}") String bankBic,
                          PaymentStatistics statistics, PaymentHubProperties props,
                          @Value("${minipay.bank.time-zone:UTC}") ZoneId bankZone, MessagingApi messaging) {
        this.messaging = messaging;
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
        String hash = fingerprint("create", c.debtorAccount(), c.creditorName(), creditorAccount, creditorAgentBic,
                c.amount(), c.chargeBearer(), c.requestedExecutionDate(), c.endToEndId(), c.remittanceInformation());

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
                payments.saveAndFlush(p);
                transitions.recordReceived(p, "Accepted from channel REST API", "api/payments");
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
     * SIMULATION until the network simulator (M5): plays the next bank on the route. The outcome is
     * written as the message that bank would send (pacs.002 or pacs.004, from the instructed agent to us)
     * and then received through exactly the same path as a real inbound message, so it is stored raw
     * and correlated like one.
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
        PaymentStatus target = targetOf(event.type());
        PaymentStateMachine.check(p.status(), target); // fail fast, before building anything
        MessageView ours = messaging.forUetr(p.uetr().toString()).stream()
                .filter(m -> m.type() == MessageType.PACS_008 && m.direction() == MessageDirection.OUTBOUND)
                .findFirst().orElse(null);
        String originalMsgId = ours != null ? ours.msgId() : null;
        String raw = switch (event.type()) {
            case ACCEPTED, SETTLED -> messaging.buildPacs002(new StatusReport(p.instructedAgentBic(), bankBic, originalMsgId,
                    p.instructionId(), p.endToEndId(), p.uetr().toString(),
                    event.type() == NetworkEvent.Type.ACCEPTED ? TransactionStatus.ACCP : TransactionStatus.ACSC,
                    null, event.reference()));
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

    /** A message from another bank about a payment we sent: pacs.002 (status) or pacs.004 (return). */
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
        if (m.type() != MessageType.PACS_002 && m.type() != MessageType.PACS_004) {
            throw new PaymentException(PaymentError.UNSUPPORTED_MESSAGE, m.type().identifier()
                    + " stored (" + m.id() + "); the hub acts on pacs.002 and pacs.004 — inbound payments arrive with M5");
        }
        Payment p = find(in.originalUetr());
        if (p.instructedAgentBic() == null
                || !Bic.parse(m.senderBic()).bic8().equals(Bic.parse(p.instructedAgentBic()).bic8())) {
            throw new PaymentException(PaymentError.INVALID_NETWORK_EVENT, m.type().identifier() + " from " + m.senderBic()
                    + ", but payment " + p.uetr() + " was sent to " + p.instructedAgentBic());
        }
        NetworkEvent event = switch (m.type()) {
            case PACS_004 -> new NetworkEvent(NetworkEvent.Type.RETURNED, in.reasonCode(), in.returnedAmount(), in.additionalInfo());
            default -> switch (in.status()) {
                case "ACCP", "ACSP" -> new NetworkEvent(NetworkEvent.Type.ACCEPTED, null, null, null);
                case "ACSC" -> new NetworkEvent(NetworkEvent.Type.SETTLED, null, null, null);
                default -> throw new PaymentException(PaymentError.UNSUPPORTED_MESSAGE, "pacs.002 " + in.status()
                        + " after SENT is stored but not acted on: a rejection by the next bank is modelled in M5");
            };
        };
        PaymentStatus target = targetOf(event.type());
        // A duplicate, or a status that arrives after a later one (an ACCP after the ACSC), must not move the
        // payment backwards. It is stored, and acknowledged, and nothing else happens.
        if (in.duplicate() || alreadyPast(p.status(), target)) {
            return new Replayable<>(PaymentMapper.toView(p), true);
        }
        String ref = m.type().identifier().substring(0, 8) + " " + m.msgId()
                + (m.type() == MessageType.PACS_002 ? " " + in.status() : "") + note;
        return apply(key, hash, p, event, "net/" + m.senderBic(), ref);
    }

    private static PaymentStatus targetOf(NetworkEvent.Type type) {
        return switch (type) {
            case ACCEPTED -> PaymentStatus.ACKED;
            case SETTLED -> PaymentStatus.SETTLED;
            case RETURNED -> PaymentStatus.RETURNED;
        };
    }

    /** Order of the after-SENT statuses: a report for an earlier stage changes nothing. */
    private static boolean alreadyPast(PaymentStatus current, PaymentStatus target) {
        List<PaymentStatus> order = List.of(PaymentStatus.SENT, PaymentStatus.ACKED, PaymentStatus.SETTLED, PaymentStatus.RETURNED);
        int c = order.indexOf(current);
        return c >= 0 && c >= order.indexOf(target);
    }

    private Replayable<PaymentView> apply(String key, String hash, Payment p, NetworkEvent event, String actor, String ref) {
        PaymentStatus target = targetOf(event.type());
        PaymentStateMachine.check(p.status(), target); // fail fast, before touching the ledger
        Supplier<Change> change = switch (event.type()) {
            case ACCEPTED -> () -> new Change(PaymentStatus.ACKED, null, "Accepted by " + p.instructedAgentBic() + " · " + ref, actor);
            case SETTLED -> () -> new Change(PaymentStatus.SETTLED, null,
                    "Settlement confirmed for " + Money.of(p.interbankSettlementAmount(), p.currency()) + " · " + ref, actor);
            case RETURNED -> {
                Money returned = bookReturn(p, event);
                yield () -> new Change(PaymentStatus.RETURNED, event.reasonCode(),
                        "Return " + event.reasonCode() + " · " + returned + " credited to " + p.debtorAccount() + " · " + ref,
                        actor);
            }
        };
        try {
            boolean done = tx.execute(s -> {
                boolean moved = transitions.transition(p.id(), p.status(), payment -> {
                    if (event.type() == NetworkEvent.Type.RETURNED) {
                        payment.reject(event.reasonCode(), "Returned by the network: " + event.reasonCode());
                    }
                    return change.get();
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
                c.amount(), c.chargeBearer(), c.requestedExecutionDate(), c.endToEndId(), c.remittanceInformation());
        return new PaymentPreview(pv.xml(), pv.notes());
    }

    /**
     * A pacs.004 return: the money comes back to our settlement account (nostro/vostro) and we credit
     * the debtor. The returned amount may be less than sent — banks on the route deducted charges — and
     * our own fee is not refunded.
     */
    private Money bookReturn(Payment p, NetworkEvent event) {
        Money sent = Money.of(p.interbankSettlementAmount(), p.currency());
        Money returned = event.returnedAmount() != null ? event.returnedAmount() : sent;
        checkReturnedAmount(p, returned);
        AccountView debtor = ledger.findAccountByNumber(p.debtorAccount()).orElseThrow();
        ledger.post(new PostEntryCommand("pay:" + p.id() + ":return", EntryType.SETTLE, p.id(), null,
                "Return " + event.reasonCode() + " · " + p.endToEndId(),
                java.util.List.of(new PostingLine(p.settlementAccountId(), Direction.D, returned),
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
