package com.minipay.settlement.internal.engine;

import com.minipay.common.money.Money;
import com.minipay.ledger.api.Direction;
import com.minipay.ledger.api.EntryType;
import com.minipay.ledger.api.PostEntryCommand;
import com.minipay.ledger.api.PostingLine;
import com.minipay.messaging.api.TransactionStatus;
import com.minipay.refdata.api.ClearingSystemView;
import com.minipay.settlement.api.TransferStatus;
import com.minipay.settlement.api.TransferView;
import com.minipay.settlement.internal.domain.Netting;
import com.minipay.settlement.internal.domain.Netting.Obligation;
import com.minipay.settlement.internal.persistence.SettlementRepository.CycleRow;
import com.minipay.settlement.internal.persistence.SettlementRepository.ParticipantRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * MiniPay Net: deferred net settlement. Payments are accepted into the open cycle and nothing moves; at the
 * cycle's close the ACH nets them multilaterally and settles only the net positions, in one booking on the
 * RTGS (T2) accounts. Then, and only then, the payments are final and go on to the payees. A net payer that
 * cannot pay is unwound ({@link Netting#settle}).
 *
 * <p>All methods run inside the caller's transaction.
 */
@Component
public class DnsEngine {

    private static final Logger log = LoggerFactory.getLogger(DnsEngine.class);
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    private final Operator op;

    DnsEngine(Operator op) {
        this.op = op;
    }

    /**
     * The cycle a payment for this settlement date joins: the open one, or a new one closing at the next cycle
     * time. Empty if no cycle is left that day (after the last cycle).
     */
    public Optional<CycleRow> cycleFor(ClearingSystemView mpnet, LocalDate date) {
        Optional<CycleRow> open = op.repo.openCycle(mpnet.code(), date);
        if (open.isPresent()) {
            return open;
        }
        ZoneId zone = ZoneId.of(mpnet.timeZone());
        Instant now = op.now();
        Optional<Instant> close = mpnet.cycleTimes().stream().sorted()
                .map(t -> t.atDate(date).atZone(zone).toInstant()).filter(i -> i.isAfter(now)).findFirst();
        if (close.isEmpty()) {
            return Optional.empty();
        }
        int seq = op.repo.lastSeq(mpnet.code(), date) + 1;
        CycleRow row = new CycleRow(mpnet.code() + "-" + YYYYMMDD.format(date) + "-" + String.format("%02d", seq),
                mpnet.code(), date, seq, "OPEN", close.get(), now, null, null, null);
        if (!op.repo.insertCycle(row)) {
            return op.repo.openCycle(mpnet.code(), date); // a concurrent arrival opened it
        }
        log.info("{} cycle {} opened, closes {}", mpnet.name(), row.id(), close.get());
        return Optional.of(row);
    }

    /**
     * Closes a cycle: nets it, unwinds whoever cannot pay, books the net positions on the RTGS accounts in one
     * entry, and only then delivers the payments. Under the locks of every participant (BIC order).
     *
     * @return the net receivers (their RTGS queues may move now)
     */
    public Set<String> settle(String cycleId) {
        CycleRow cycle = op.repo.lockCycle(cycleId).orElseThrow();
        if (!"OPEN".equals(cycle.status())) {
            return Set.of();
        }
        ClearingSystemView mpnet = op.system(cycle.system());
        List<TransferView> payments = op.repo.inCycle(cycleId).stream()
                .filter(t -> t.status() == TransferStatus.ACCEPTED).toList();
        List<String> everyone = op.repo.participants().stream().map(ParticipantRow::bic).toList();
        op.repo.lockParticipants(everyone);
        Map<String, BigDecimal> available = new HashMap<>();
        Set<String> involved = new TreeSet<>();
        payments.forEach(t -> {
            involved.add(t.payerBic());
            involved.add(t.payeeBic());
        });
        involved.forEach(b -> available.put(b, op.rtgsAccount(b).available().amount()));
        Map<UUID, TransferView> byId = new HashMap<>();
        List<Obligation> obligations = new ArrayList<>();
        for (TransferView t : payments) {
            byId.put(t.id(), t);
            obligations.add(new Obligation(t.id(), t.payerBic(), t.payeeBic(), t.amount().amount()));
        }
        Netting.Outcome outcome = Netting.settle(obligations, available, involved);
        String ccy = mpnet.currency();

        List<PostingLine> lines = new ArrayList<>();
        for (Netting.Position p : outcome.positions().values()) {
            int sign = p.net().signum();
            if (sign != 0) {
                lines.add(new PostingLine(op.rtgsAccount(p.bic()).id(), sign < 0 ? Direction.D : Direction.C,
                        Money.of(p.net().abs(), ccy)));
            }
        }
        UUID entryId = null;
        if (!lines.isEmpty()) {
            entryId = op.ledger.post(new PostEntryCommand("mpnet:" + cycleId, EntryType.SETTLE, null, cycle.businessDate(),
                    mpnet.name() + " cycle " + cycleId + ": net positions of " + outcome.settled().size() + " payments", lines))
                    .value().id();
        }
        Instant now = op.now();
        Set<String> unwound = new LinkedHashSet<>(outcome.unwound());
        for (Obligation o : outcome.settled()) {
            TransferView t = byId.get(o.id());
            op.repo.updateStatus(t.id(), TransferStatus.SETTLED, null, null, entryId, now);
            var fwd = op.forward(t);
            var acsc = op.status(t, t.payerBic(), TransactionStatus.ACSC, null, null);
            op.record(t, TransferStatus.SETTLED, "Settled in cycle " + cycleId + " (net settlement on " + mpnet.settlesIn()
                    + ") · forwarded to " + t.payeeBic() + " (" + fwd.msgId() + ") · pacs.002 ACSC → " + t.payerBic(),
                    "sys/" + mpnet.code());
        }
        for (Obligation o : outcome.removed()) {
            TransferView t = byId.get(o.id());
            String culprit = unwound.stream().filter(b -> b.equals(t.payerBic()) || b.equals(t.payeeBic())).findFirst().orElse("?");
            String detail = "Unwound from cycle " + cycleId + ": " + culprit + " could not cover its net debit position on "
                    + mpnet.settlesIn();
            op.repo.updateStatus(t.id(), TransferStatus.REJECTED, "AM04", detail, null, now);
            var rjct = op.status(t, t.payerBic(), TransactionStatus.RJCT, "AM04", detail);
            op.record(t, TransferStatus.REJECTED, "AM04 " + detail + " · pacs.002 RJCT → " + t.payerBic() + " (" + rjct.msgId() + ")",
                    "sys/" + mpnet.code());
        }
        outcome.positions().values().forEach(p -> op.repo.insertPosition(cycleId, p.bic(), p.pays(), p.receives()));
        op.repo.settleCycle(cycleId, now, entryId, unwound.isEmpty() ? null : String.join(",", unwound));
        log.info("{} cycle {} settled: {} payments, {} unwound ({}), net {} {}", mpnet.name(), cycleId, outcome.settled().size(),
                outcome.removed().size(), unwound, ccy, Netting.multilateralTotal(outcome.settled()));
        Set<String> receivers = new LinkedHashSet<>();
        outcome.positions().values().stream().filter(p -> p.net().signum() > 0).forEach(p -> receivers.add(p.bic()));
        return receivers;
    }

}
