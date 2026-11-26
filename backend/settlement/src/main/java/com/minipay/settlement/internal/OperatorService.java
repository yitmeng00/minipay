package com.minipay.settlement.internal;

import com.minipay.common.id.Bic;
import com.minipay.common.money.Money;
import com.minipay.ledger.api.AccountView;
import com.minipay.ledger.api.StatementLine;
import com.minipay.messaging.api.CreditTransfer;
import com.minipay.messaging.api.InboundMessage;
import com.minipay.messaging.api.MessageType;
import com.minipay.messaging.api.MessageView;
import com.minipay.messaging.api.PaymentReturn;
import com.minipay.messaging.api.TransactionStatus;
import com.minipay.refdata.api.ClearingSystemView;
import com.minipay.refdata.api.RoutingMethod;
import com.minipay.settlement.api.Replayed;
import com.minipay.settlement.api.SettlementError;
import com.minipay.settlement.api.SettlementException;
import com.minipay.settlement.api.SettlementOperatorApi;
import com.minipay.settlement.api.TransferStatus;
import com.minipay.settlement.api.TransferView;
import com.minipay.settlement.api.Views;
import com.minipay.settlement.internal.config.SettlementProperties;
import com.minipay.settlement.internal.domain.Netting;
import com.minipay.settlement.internal.engine.DnsEngine;
import com.minipay.settlement.internal.engine.InstantEngine;
import com.minipay.settlement.internal.engine.Operator;
import com.minipay.settlement.internal.engine.RtgsEngine;
import com.minipay.settlement.internal.persistence.SettlementRepository;
import com.minipay.settlement.internal.persistence.SettlementRepository.CycleRow;
import com.minipay.settlement.internal.persistence.SettlementRepository.NewTransfer;
import com.minipay.settlement.internal.persistence.SettlementRepository.ParticipantRow;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The operator: takes the participants' messages, hands each payment to its system's engine, and answers the
 * participants' screens. See ADR-008.
 *
 * <h2>Receiving</h2>
 * The message is stored first, whatever happens next (rule 8). A pacs.008 names its system in
 * SttlmInf/ClrSys; a pacs.004 is a return and settles in the RTGS like a payment; a pacs.002 is an instant
 * payee's answer. A payment the operator cannot take is REJECTED with an ISO reason and the payer is told.
 *
 * <h2>Transactions</h2>
 * Each step is one transaction: the booking on the central bank's ledger, the transfer's status and history,
 * and the outgoing messages (through the hub's outbox). After it commits, the RTGS queues of whoever was
 * credited are tried again, each in a transaction of its own: liquidity flows on.
 */
@Service
public class OperatorService implements SettlementOperatorApi, ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OperatorService.class);

    private final Operator op;
    private final SettlementRepository repo;
    private final RtgsEngine rtgs;
    private final DnsEngine dns;
    private final InstantEngine instant;
    private final SettlementProperties props;
    private final TransactionTemplate tx;

    OperatorService(Operator op, SettlementRepository repo, RtgsEngine rtgs, DnsEngine dns, InstantEngine instant,
                    SettlementProperties props, TransactionTemplate tx) {
        this.op = op;
        this.repo = repo;
        this.rtgs = rtgs;
        this.dns = dns;
        this.instant = instant;
        this.props = props;
        this.tx = tx;
    }

    /** The operator registers its participants at start-up (their accounts come from the ledger seed). */
    @Override
    public void run(ApplicationArguments args) {
        if (!props.operator()) {
            return;
        }
        for (SettlementProperties.Participant p : props.participants()) {
            String bic = Bic.parse(p.bic()).value();
            repo.upsertParticipant(new ParticipantRow(bic, p.name(), p.simulated(), SettlementProperties.rtgsAccount(bic),
                    SettlementProperties.instantAccount(bic)));
        }
        log.info("Settlement operator: {} participants", props.participants().size());
    }

    @Override
    public boolean isOperator() {
        return props.operator();
    }

    private void requireOperator() {
        if (!props.operator()) {
            throw new SettlementException(SettlementError.NOT_OPERATOR,
                    "This instance does not run the clearing systems; the operator (the central bank) does");
        }
    }

    // ------------------------------------------------------------------ receiving

    @Override
    public Views.Receipt receive(String raw) {
        requireOperator();
        InboundMessage in = op.messaging().receive(raw);
        MessageView m = in.message();
        if (!in.valid()) {
            throw new SettlementException(SettlementError.INVALID_MESSAGE,
                    "Message stored as INVALID (" + m.id() + "): " + String.join("; ", m.errors()));
        }
        if (in.duplicate()) {
            Optional<TransferView> known = repo.findByMessage(m.id());
            return new Views.Receipt(known.map(TransferView::id).orElse(null), known.map(TransferView::status).orElse(null),
                    "Already received", true);
        }
        return switch (m.type()) {
            case PACS_008 -> payment(m);
            case PACS_004 -> paymentReturn(m);
            case PACS_002 -> answer(m, in);
            default -> throw new SettlementException(SettlementError.UNSUPPORTED_MESSAGE,
                    m.type().identifier() + " stored (" + m.id() + "); the clearing systems do not act on it");
        };
    }

    private Views.Receipt payment(MessageView m) {
        CreditTransfer t = op.messaging().creditTransfer(m.id());
        String code = t.clearing() != null && t.clearing().system() != null ? t.clearing().system() : null;
        ClearingSystemView system;
        try {
            // A payment to the central bank that names no system is a T2 payment.
            system = code != null ? op.system(code) : op.system(RoutingMethod.RTGS, t.interbankAmount().currencyCode());
        } catch (IllegalStateException e) {
            system = op.system(RoutingMethod.RTGS, "EUR");
            return reject(m, system, t.interbankAmount(), t.instructedAgentBic(), t.settlementDate(), "NORM", "AG01",
                    "Unknown clearing system " + code);
        }
        String priority = t.clearing() != null && "HIGH".equals(t.clearing().priority()) ? "HIGH" : "NORM";
        String problem = null;
        String reason = null;
        LocalDate today = op.today(system);
        Instant now = op.now();
        if (op.participant(m.senderBic()).filter(p -> !p.simulated()).isEmpty()) {
            reason = "AG01";
            problem = m.senderBic() + " is not a participant of " + system.name();
        } else if (!Bic.parse(t.instructingAgentBic()).bic8().equals(Bic.parse(m.senderBic()).bic8())) {
            reason = "AG01";
            problem = "InstgAgt " + t.instructingAgentBic() + " is not the sender " + m.senderBic();
        } else if (op.participant(t.instructedAgentBic()).isEmpty()) {
            // CNOR: "creditor bank is not registered under this BIC in the CSM".
            reason = "CNOR";
            problem = t.instructedAgentBic() + " is not a participant of " + system.name();
        } else if (!t.interbankAmount().currencyCode().equals(system.currency())) {
            reason = "AM03";
            problem = system.name() + " settles " + system.currency() + " only";
        } else if (t.settlementDate().isBefore(today)) {
            reason = "DT01";
            problem = "Settlement date " + t.settlementDate() + " is in the past (" + system.name() + " business date " + today + ")";
        } else if (system.kind() == RoutingMethod.INSTANT && !t.settlementDate().equals(today)) {
            reason = "DT01";
            problem = "An instant payment settles today (" + today + "), not on " + t.settlementDate();
        } else if (system.kind() == RoutingMethod.RTGS && t.settlementDate().equals(today)
                && !now.isBefore(system.cutoffTime().atDate(today).atZone(ZoneId.of(system.timeZone())).toInstant())) {
            reason = "TM01";
            problem = "Received after the " + system.name() + " cut-off " + system.cutoffTime() + " " + system.timeZone();
        } else if (system.kind() == RoutingMethod.INSTANT && !(t.clearing() != null && t.clearing().instant())) {
            reason = "AG01";
            problem = system.name() + " takes instant payments only (PmtTpInf/LclInstrm INST)";
        }
        String payee = op.participant(t.instructedAgentBic()).map(ParticipantRow::bic).orElse(Bic.parse(t.instructedAgentBic()).value());
        if (problem != null) {
            return reject(m, system, t.interbankAmount(), payee, t.settlementDate(), priority, reason, problem);
        }
        String payer = op.participant(m.senderBic()).orElseThrow().bic();
        ClearingSystemView sys = system;
        NewTransfer base = new NewTransfer(UUID.randomUUID(), system.code(), system.kind().name(), "pacs.008", m.id(), m.msgId(),
                UUID.fromString(t.uetr()), t.endToEndId(), t.instructionId(), payer, payee, t.interbankAmount(), priority,
                t.settlementDate(), null, null,
                system.kind() == RoutingMethod.INSTANT ? now.plusSeconds(system.timeoutSeconds()) : null, null, null, now);
        return switch (system.kind()) {
            case RTGS -> rtgsArrival(base, "Received pacs.008 " + m.msgId() + " (" + priority + ")");
            case DNS -> dnsArrival(base, sys);
            case INSTANT -> instantArrival(base, sys);
            default -> throw new IllegalStateException();
        };
    }

    private Views.Receipt rtgsArrival(NewTransfer base, String detail) {
        NewTransfer queued = with(base, TransferStatus.QUEUED, null);
        Set<String> credited = tx.execute(s -> {
            repo.insert(queued);
            TransferView t = repo.find(queued.id()).orElseThrow();
            repo.event(t.id(), null, TransferStatus.QUEUED, detail, "net/" + base.payerBic());
            return rtgs.process(base.payerBic(), base.id());
        });
        flow(credited);
        TransferView t = repo.find(base.id()).orElseThrow();
        return new Views.Receipt(t.id(), t.status(), t.status() == TransferStatus.SETTLED ? "Settled" : "Queued", false);
    }

    private Views.Receipt dnsArrival(NewTransfer base, ClearingSystemView mpnet) {
        return tx.execute(s -> {
            Optional<CycleRow> cycle = dns.cycleFor(mpnet, base.settlementDate());
            if (cycle.isEmpty()) {
                return rejectInTx(with(base, TransferStatus.REJECTED, null), "TM01",
                        "No " + mpnet.name() + " cycle left on " + base.settlementDate());
            }
            NewTransfer accepted = with(base, TransferStatus.ACCEPTED, cycle.get().id());
            repo.insert(accepted);
            TransferView t = repo.find(accepted.id()).orElseThrow();
            var acsp = op.status(t, t.payerBic(), TransactionStatus.ACSP, null, null);
            repo.event(t.id(), null, TransferStatus.ACCEPTED, "Received pacs.008 " + t.msgId() + " · in cycle " + cycle.get().id()
                    + ", which settles at " + cycle.get().scheduledClose() + " · pacs.002 ACSP → " + t.payerBic() + " (" + acsp.msgId() + ")",
                    "net/" + t.payerBic());
            return new Views.Receipt(t.id(), t.status(), "In cycle " + cycle.get().id(), false);
        });
    }

    private Views.Receipt instantArrival(NewTransfer base, ClearingSystemView inst) {
        return tx.execute(s -> {
            repo.lockParticipants(List.of(base.payerBic()));
            Money available = op.instantAccount(base.payerBic()).available();
            if (available.isLessThan(base.amount())) {
                return rejectInTx(with(base, TransferStatus.REJECTED, null), "AM04", "Insufficient prefunded position: "
                        + base.payerBic() + " has " + available + " available on " + inst.name());
            }
            NewTransfer pending = with(base, TransferStatus.PENDING, null);
            repo.insert(pending);
            TransferView t = repo.find(pending.id()).orElseThrow();
            repo.event(t.id(), null, TransferStatus.PENDING, "Received pacs.008 " + t.msgId() + " (instant)", "net/" + t.payerBic());
            instant.reserveAndForward(t);
            return new Views.Receipt(t.id(), TransferStatus.PENDING, "Waiting for " + t.payeeBic(), false);
        });
    }

    /** A return (pacs.004) of a payment that settled through us goes back through the RTGS, gross. */
    private Views.Receipt paymentReturn(MessageView m) {
        PaymentReturn r = op.messaging().paymentReturn(m.id());
        Optional<TransferView> original = repo.forUetr(UUID.fromString(r.originalUetr())).stream()
                .filter(t -> t.status() == TransferStatus.SETTLED && t.messageType().equals("pacs.008")
                        && Bic.parse(t.payeeBic()).bic8().equals(Bic.parse(m.senderBic()).bic8()))
                .findFirst();
        ClearingSystemView t2 = op.system(RoutingMethod.RTGS, r.returnedAmount().currencyCode());
        if (original.isEmpty()) {
            throw new SettlementException(SettlementError.INVALID_MESSAGE, "pacs.004 stored (" + m.id() + "): no payment "
                    + r.originalUetr() + " settled to " + m.senderBic() + " here");
        }
        TransferView o = original.get();
        Instant now = op.now();
        LocalDate today = op.today(t2);
        NewTransfer base = new NewTransfer(UUID.randomUUID(), t2.code(), "RTGS", "pacs.004", m.id(), m.msgId(), o.uetr(),
                o.endToEndId(), o.instructionId(), o.payeeBic(), o.payerBic(), r.returnedAmount(), "NORM",
                r.settlementDate() != null && r.settlementDate().isAfter(today) ? r.settlementDate() : today,
                null, null, null, null, null, now);
        return rtgsArrival(base, "Received pacs.004 " + m.msgId() + " (" + r.reasonCode() + "): return of " + o.msgId()
                + " settled " + o.settledAt());
    }

    /** An instant payee's answer: ACCP (accept) or RJCT, for a payment we forwarded to it. */
    private Views.Receipt answer(MessageView m, InboundMessage in) {
        Optional<TransferView> t = repo.forUetr(UUID.fromString(in.originalUetr())).stream()
                .filter(x -> x.kind().equals("INSTANT") && Bic.parse(x.payeeBic()).bic8().equals(Bic.parse(m.senderBic()).bic8()))
                .findFirst();
        if (t.isEmpty()) {
            throw new SettlementException(SettlementError.INVALID_MESSAGE, "pacs.002 stored (" + m.id()
                    + "): no instant payment " + in.originalUetr() + " to " + m.senderBic());
        }
        boolean accepted = "ACCP".equals(in.status()) || "ACSC".equals(in.status()) || "ACSP".equals(in.status());
        TransferView after = tx.execute(s -> instant.answer(t.get().id(), accepted, accepted ? null : in.reasonCode(),
                in.additionalInfo(), "net/" + m.senderBic()));
        return new Views.Receipt(after.id(), after.status(), in.status() + " from " + m.senderBic(), false);
    }

    private Views.Receipt reject(MessageView m, ClearingSystemView system, Money amount, String payee, LocalDate date,
                                 String priority, String code, String detail) {
        CreditTransfer t = op.messaging().creditTransfer(m.id());
        String payer = Bic.isValid(m.senderBic()) ? Bic.parse(m.senderBic()).value() : m.senderBic();
        NewTransfer rejected = new NewTransfer(UUID.randomUUID(), system.code(), system.kind().name(), "pacs.008", m.id(),
                m.msgId(), UUID.fromString(t.uetr()), t.endToEndId(), t.instructionId(), payer, payee, amount, priority, date,
                TransferStatus.REJECTED, null, system.kind() == RoutingMethod.INSTANT ? op.now() : null, code, detail, op.now());
        return tx.execute(s -> rejectInTx(rejected, code, detail));
    }

    private Views.Receipt rejectInTx(NewTransfer n, String code, String detail) {
        NewTransfer r = new NewTransfer(n.id(), n.system(), n.kind(), n.messageType(), n.messageId(), n.msgId(), n.uetr(),
                n.endToEndId(), n.instructionId(), n.payerBic(), n.payeeBic(), n.amount(), n.priority(), n.settlementDate(),
                TransferStatus.REJECTED, null, n.kind().equals("INSTANT") ? (n.deadline() != null ? n.deadline() : op.now()) : null,
                code, detail, n.receivedAt());
        repo.insert(r);
        TransferView t = repo.find(r.id()).orElseThrow();
        var rjct = op.status(t, t.payerBic(), TransactionStatus.RJCT, code, detail);
        repo.event(t.id(), null, TransferStatus.REJECTED, code + " " + detail + " · pacs.002 RJCT → " + t.payerBic() + " ("
                + rjct.msgId() + ")", "sys/" + t.system());
        log.info("{} rejected {} {}: {}", t.system(), t.msgId(), code, detail);
        return new Views.Receipt(t.id(), TransferStatus.REJECTED, code + " " + detail, false);
    }

    private static NewTransfer with(NewTransfer n, TransferStatus status, String cycleId) {
        return new NewTransfer(n.id(), n.system(), n.kind(), n.messageType(), n.messageId(), n.msgId(), n.uetr(), n.endToEndId(),
                n.instructionId(), n.payerBic(), n.payeeBic(), n.amount(), n.priority(), n.settlementDate(), status, cycleId,
                n.deadline(), n.reasonCode(), n.reasonDetail(), n.receivedAt());
    }

    /** Liquidity flows on: credited participants' queues are tried, each in its own transaction, until nothing moves. */
    void flow(Collection<String> credited) {
        Deque<String> work = new ArrayDeque<>(credited);
        for (int guard = 0; !work.isEmpty() && guard < 100; guard++) {
            String bic = work.poll();
            Set<String> next = tx.execute(s -> rtgs.process(bic));
            next.stream().filter(b -> !work.contains(b)).forEach(work::add);
        }
    }

    // ------------------------------------------------------------------ the timers (OperatorScheduler)

    /** Retries every queue: the backstop for a credit whose follow-up did not run (a crash after the commit). */
    public void retryQueues() {
        flow(repo.payersWithQueue());
    }

    public int closeDueCycles() {
        int n = 0;
        for (CycleRow c : repo.cyclesDue(op.now())) {
            flow(tx.execute(s -> dns.settle(c.id())));
            n++;
        }
        return n;
    }

    public int endOfDay() {
        Integer n = tx.execute(s -> rtgs.rejectAtCutoff(op.system(RoutingMethod.RTGS, "EUR")));
        return n == null ? 0 : n;
    }

    public void instantTimers() {
        for (UUID id : repo.instantDue(op.now())) {
            try {
                tx.executeWithoutResult(s -> instant.due(id));
            } catch (RuntimeException e) {
                log.warn("Instant timer for {} failed: {}", id, e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ participants' commands (U2A)

    @Override
    public Replayed<TransferView> moveUp(String key, UUID transferId, String participantBic) {
        requireOperator();
        return command(key, "move-up|" + transferId + "|" + participantBic, () -> {
            TransferView t = queued(transferId, participantBic);
            List<TransferView> queue = repo.rtgsQueue(t.payerBic()).stream().filter(x -> x.priority().equals(t.priority())).toList();
            int i = indexOf(queue, t.id());
            if (i == 0) {
                throw new SettlementException(SettlementError.ILLEGAL_COMMAND, "Already first among the " + t.priority() + " payments");
            }
            TransferView before = queue.get(i - 1);
            repo.reorder(t.id(), before.queueSeq(), t.priority(), op.now());
            repo.reorder(before.id(), t.queueSeq(), before.priority(), op.now());
            repo.event(t.id(), null, TransferStatus.QUEUED, "Moved up the queue, before " + before.msgId(), "u2a/" + participantBic);
        }, transferId);
    }

    @Override
    public Replayed<TransferView> changePriority(String key, UUID transferId, String priority, String participantBic) {
        requireOperator();
        if (!"HIGH".equals(priority) && !"NORM".equals(priority)) {
            throw new SettlementException(SettlementError.INVALID_REQUEST, "Priority is HIGH (urgent) or NORM");
        }
        return command(key, "priority|" + transferId + "|" + priority + "|" + participantBic, () -> {
            TransferView t = queued(transferId, participantBic);
            if (t.priority().equals(priority)) {
                return;
            }
            repo.reorder(t.id(), t.queueSeq(), priority, op.now());
            repo.event(t.id(), null, TransferStatus.QUEUED, "Priority " + t.priority() + " → " + priority, "u2a/" + participantBic);
        }, transferId);
    }

    private Replayed<TransferView> command(String key, String request, Runnable change, UUID transferId) {
        String hash = sha256(request);
        Boolean replayed = tx.execute(s -> {
            repo.lockKey(key);
            Optional<String> stored = repo.keyHash(key);
            if (stored.isPresent()) {
                if (!stored.get().equals(hash)) {
                    throw new SettlementException(SettlementError.IDEMPOTENCY_CONFLICT, "Idempotency-Key " + key
                            + " was used for a different request");
                }
                return true;
            }
            TransferView t = repo.lock(transferId).orElseThrow(() -> notFound(transferId));
            repo.lockParticipants(List.of(t.payerBic()));
            change.run();
            repo.saveKey(key, hash, op.now());
            return false;
        });
        if (!Boolean.TRUE.equals(replayed)) {
            flow(List.of(repo.find(transferId).orElseThrow().payerBic()));
        }
        return new Replayed<>(transfer(transferId).transfer(), Boolean.TRUE.equals(replayed));
    }

    private TransferView queued(UUID id, String participantBic) {
        TransferView t = repo.find(id).orElseThrow(() -> notFound(id));
        if (!Bic.parse(t.payerBic()).bic8().equals(Bic.parse(participantBic).bic8())) {
            throw new SettlementException(SettlementError.ILLEGAL_COMMAND, "Only the payer manages its queue");
        }
        if (t.status() != TransferStatus.QUEUED || !t.kind().equals("RTGS")) {
            throw new SettlementException(SettlementError.ILLEGAL_COMMAND, "Only a queued RTGS payment can be reordered (it is "
                    + t.status() + ")");
        }
        return t;
    }

    @Override
    public Replayed<Views.DnsCycle> closeCycle(String key) {
        requireOperator();
        ClearingSystemView mpnet = op.system(RoutingMethod.DNS, "EUR");
        LocalDate today = op.today(mpnet);
        String hash = sha256("close-cycle|" + today);
        Optional<String> stored = repo.keyHash(key);
        if (stored.isPresent()) {
            if (!stored.get().equals(hash)) {
                throw new SettlementException(SettlementError.IDEMPOTENCY_CONFLICT, "Idempotency-Key " + key + " was used for a different request");
            }
            List<CycleRow> cycles = repo.cycles(today);
            return new Replayed<>(cycles.isEmpty() ? null : cycle(cycles.get(0)), true);
        }
        Optional<CycleRow> open = repo.openCycle(mpnet.code(), today);
        if (open.isEmpty()) {
            throw new SettlementException(SettlementError.ILLEGAL_COMMAND, "No open " + mpnet.name() + " cycle today: nothing to settle");
        }
        Set<String> receivers = tx.execute(s -> {
            repo.lockKey(key);
            if (repo.keyHash(key).isPresent()) {
                return Set.<String>of();
            }
            repo.saveKey(key, hash, op.now());
            return dns.settle(open.get().id());
        });
        flow(receivers);
        return new Replayed<>(cycle(repo.findCycle(open.get().id()).orElseThrow()), false);
    }

    // ------------------------------------------------------------------ queries

    @Override
    public List<Views.SystemInfo> systems() {
        return op.refdata().clearingSystems(LocalDate.now(ZoneId.of("UTC"))).stream()
                .map(s -> new Views.SystemInfo(s.code(), s.name(), s.kind().name(), s.currency(), s.operatorBic(), s.timeZone(),
                        s.cutoffTime(), s.cycleTimes(), s.timeoutSeconds())).toList();
    }

    @Override
    public List<Views.Participant> participants() {
        requireOperator();
        return repo.participants().stream().map(p -> {
            AccountView r = op.rtgsAccount(p.bic());
            AccountView i = op.instantAccount(p.bic());
            return new Views.Participant(p.bic(), p.name(), p.simulated(), p.rtgsAccount(), p.instantAccount(), r.booked(),
                    i.available(), i.reserved());
        }).toList();
    }

    @Override
    public Views.Rtgs rtgs(String participantBic) {
        requireOperator();
        ParticipantRow p = participantOrThrow(participantBic);
        ClearingSystemView t2 = op.system(RoutingMethod.RTGS, "EUR");
        LocalDate today = op.today(t2);
        AccountView account = op.rtgsAccount(p.bic());
        List<TransferView> queue = repo.rtgsQueue(p.bic());
        Map<UUID, String> why = rtgs.waiting(p.bic(), queue);
        List<TransferView> explained = queue.stream().map(t -> t.withWaiting(why.get(t.id()))).toList();
        List<TransferView> incoming = repo.queuedTo(p.bic());
        Money zero = Money.zero(account.currency());
        Money out = explained.stream().map(TransferView::amount).reduce(zero, Money::plus);
        Money in = incoming.stream().map(TransferView::amount).reduce(zero, Money::plus);
        List<Views.BalancePoint> points = new ArrayList<>();
        var statement = op.ledger().statement(account.id(), today);
        points.add(new Views.BalancePoint(today.atStartOfDay(ZoneId.of(t2.timeZone())).toInstant(), statement.openingBooked()));
        for (StatementLine l : statement.lines()) {
            points.add(new Views.BalancePoint(l.bookedAt(), l.balance()));
        }
        Instant cutoffAt = t2.cutoffTime().atDate(today).atZone(ZoneId.of(t2.timeZone())).toInstant();
        Instant startOfDay = today.atStartOfDay(ZoneId.of(t2.timeZone())).toInstant();
        List<TransferView> recent = repo.recent("RTGS", p.bic(), startOfDay, 50).stream().filter(t -> t.status().isFinal()).toList();
        return new Views.Rtgs(t2.code(), t2.name(), p.bic(), p.name(), today, t2.timeZone(), t2.cutoffTime(), cutoffAt,
                account.available(), out, explained.size(), in, incoming.size(), account.available().minus(out).plus(in),
                points, explained, recent);
    }

    @Override
    public List<Views.DnsCycle> cycles(LocalDate date) {
        requireOperator();
        ClearingSystemView mpnet = op.system(RoutingMethod.DNS, "EUR");
        return repo.cycles(date != null ? date : op.today(mpnet)).stream().map(this::cycle).toList();
    }

    @Override
    public Views.DnsCycle cycle(String id) {
        requireOperator();
        return cycle(repo.findCycle(id).orElseThrow(() -> new SettlementException(SettlementError.NOT_FOUND, "No cycle " + id)));
    }

    private Views.DnsCycle cycle(CycleRow c) {
        ClearingSystemView mpnet = op.system(c.system());
        List<TransferView> all = repo.inCycle(c.id());
        TransferStatus counted = "OPEN".equals(c.status()) ? TransferStatus.ACCEPTED : TransferStatus.SETTLED;
        List<Netting.Obligation> obligations = all.stream().filter(t -> t.status() == counted)
                .map(t -> new Netting.Obligation(t.id(), t.payerBic(), t.payeeBic(), t.amount().amount())).toList();
        List<ParticipantRow> parts = repo.participants();
        List<String> bics = parts.stream().map(ParticipantRow::bic).toList();
        var gross = Netting.gross(obligations);
        String ccy = mpnet.currency();
        List<Views.MatrixRow> rows = bics.stream().map(payer -> new Views.MatrixRow(payer, bics.stream()
                .map(payee -> Money.of(gross.getOrDefault(payer, Map.of()).getOrDefault(payee, BigDecimal.ZERO), ccy)).toList())).toList();
        List<Views.Position> positions = Netting.positions(obligations, bics).values().stream()
                .map(p -> new Views.Position(p.bic(), parts.stream().filter(x -> x.bic().equals(p.bic())).map(ParticipantRow::name)
                        .findFirst().orElse(p.bic()), Money.of(p.pays(), ccy), Money.of(p.receives(), ccy), Money.of(p.net(), ccy)))
                .toList();
        int rejected = (int) all.stream().filter(t -> t.status() == TransferStatus.REJECTED).count();
        return new Views.DnsCycle(c.id(), c.system(), mpnet.name(), c.businessDate(), c.seq(), c.status(), c.scheduledClose(),
                c.settledAt(), obligations.size(), rejected, bics, rows, positions, Money.of(Netting.grossTotal(obligations), ccy),
                Money.of(Netting.bilateralTotal(obligations), ccy), Money.of(Netting.multilateralTotal(obligations), ccy),
                c.unwound() == null ? List.of() : List.of(c.unwound().split(",")));
    }

    @Override
    public Views.InstantPosition instant(String participantBic) {
        requireOperator();
        ParticipantRow p = participantOrThrow(participantBic);
        ClearingSystemView inst = op.system(RoutingMethod.INSTANT, "EUR");
        AccountView account = op.instantAccount(p.bic());
        Instant startOfDay = op.today(inst).atStartOfDay(ZoneId.of(inst.timeZone())).toInstant();
        List<TransferView> today = repo.recent("INSTANT", p.bic(), startOfDay, 2000);
        int accepted = (int) today.stream().filter(t -> t.status() == TransferStatus.SETTLED).count();
        int timedOut = (int) today.stream().filter(t -> "AB05".equals(t.reasonCode())).count();
        int rejected = (int) today.stream().filter(t -> t.status() == TransferStatus.REJECTED).count() - timedOut;
        List<Long> times = today.stream().filter(t -> t.settledAt() != null)
                .map(t -> Duration.between(t.receivedAt(), t.settledAt()).toMillis()).sorted().toList();
        Long median = times.isEmpty() ? null : times.get(times.size() / 2);
        Long p99 = times.isEmpty() ? null : times.get(Math.min(times.size() - 1, (int) Math.ceil(times.size() * 0.99) - 1));
        return new Views.InstantPosition(inst.code(), inst.name(), inst.timeoutSeconds(), p.bic(), account.available(),
                account.reserved(), today.size(), accepted, rejected, timedOut, median, p99,
                today.stream().limit(30).toList());
    }

    @Override
    public Views.TransferDetail transfer(UUID id) {
        requireOperator();
        TransferView t = repo.find(id).orElseThrow(() -> notFound(id));
        return new Views.TransferDetail(t, repo.events(id));
    }

    @Override
    public List<TransferView> transfersForUetr(UUID uetr) {
        requireOperator();
        return repo.forUetr(uetr);
    }

    @Override
    public List<TransferView> transfersSettledBy(UUID entryId) {
        return repo.settledBy(entryId);
    }

    @Override
    public Optional<TransferView> findTransfer(UUID id) {
        return repo.find(id);
    }

    // ------------------------------------------------------------------ helpers

    private ParticipantRow participantOrThrow(String bic) {
        return op.participant(bic).orElseThrow(() -> new SettlementException(SettlementError.NOT_FOUND,
                bic + " is not a participant of the clearing systems"));
    }

    private static int indexOf(List<TransferView> list, UUID id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private static SettlementException notFound(UUID id) {
        return new SettlementException(SettlementError.NOT_FOUND, "No transfer " + id);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

}
