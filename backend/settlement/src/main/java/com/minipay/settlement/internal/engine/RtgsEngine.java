package com.minipay.settlement.internal.engine;

import com.minipay.common.money.Money;
import com.minipay.ledger.api.Direction;
import com.minipay.ledger.api.EntryType;
import com.minipay.ledger.api.JournalEntryView;
import com.minipay.ledger.api.PostEntryCommand;
import com.minipay.ledger.api.PostingLine;
import com.minipay.messaging.api.TransactionStatus;
import com.minipay.refdata.api.ClearingSystemView;
import com.minipay.refdata.api.RoutingMethod;
import com.minipay.settlement.api.TransferStatus;
import com.minipay.settlement.api.TransferView;
import com.minipay.settlement.internal.domain.QueuePolicy;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * T2: real-time gross settlement. Each payment settles on its own, in central bank money, the moment the
 * payer's account can cover it; then it is final. A payment that cannot be covered waits in the payer's
 * queue ({@link QueuePolicy}) until liquidity arrives: a payment to it, a DNS credit, or the payer reordering
 * its queue. Whatever is still queued at the cut-off is rejected (AM04): an RTGS never carries a payment
 * into the next day.
 *
 * <p>All methods run inside the caller's transaction.
 */
@Component
public class RtgsEngine {

    private static final Logger log = LoggerFactory.getLogger(RtgsEngine.class);

    private final Operator op;

    RtgsEngine(Operator op) {
        this.op = op;
    }

    /**
     * Settles what the payer's queue allows now, under the payer's lock.
     *
     * @return the payees that were credited: their own queues may move now (after this transaction commits)
     */
    public Set<String> process(String payerBic) {
        return process(payerBic, null);
    }

    /** @param arrived the payment that just came in (its history says "settled on arrival"), or null */
    public Set<String> process(String payerBic, UUID arrived) {
        op.repo.lockParticipants(List.of(payerBic));
        List<TransferView> queue = op.repo.rtgsQueue(payerBic);
        Set<String> credited = new LinkedHashSet<>();
        if (queue.isEmpty()) {
            return credited;
        }
        ClearingSystemView t2 = op.system(RoutingMethod.RTGS, queue.get(0).amount().currencyCode());
        Money available = op.rtgsAccount(payerBic).available();
        QueuePolicy.Plan plan = QueuePolicy.plan(items(queue), available.amount(), op.today(t2), available.currencyCode());
        Map<UUID, TransferView> byId = new java.util.HashMap<>();
        queue.forEach(t -> byId.put(t.id(), t));
        for (UUID id : plan.settle()) {
            TransferView t = byId.get(id);
            settle(t, t.id().equals(arrived) ? "Settled on arrival" : "Settled from the queue");
            credited.add(t.payeeBic());
        }
        return credited;
    }

    private void settle(TransferView t, String how) {
        JournalEntryView entry = op.ledger.post(new PostEntryCommand("rtgs:" + t.id(), EntryType.SETTLE, t.id(), t.settlementDate(),
                t.system() + " " + t.messageType() + " " + t.msgId() + " · " + t.payerBic() + " → " + t.payeeBic(),
                List.of(new PostingLine(op.rtgsAccount(t.payerBic()).id(), Direction.D, t.amount()),
                        new PostingLine(op.rtgsAccount(t.payeeBic()).id(), Direction.C, t.amount())))).value();
        Instant now = op.now();
        op.repo.updateStatus(t.id(), TransferStatus.SETTLED, null, null, entry.id(), now);
        var fwd = op.forward(t);
        String detail = how + ": debited " + t.payerBic() + ", credited " + t.payeeBic() + " " + t.amount()
                + " (final) · " + t.messageType() + " forwarded to " + t.payeeBic() + " (" + fwd.msgId() + ")";
        if (t.messageType().startsWith("pacs.008")) {
            var acsc = op.status(t, t.payerBic(), TransactionStatus.ACSC, null, null);
            detail += " · pacs.002 ACSC → " + t.payerBic() + " (" + acsc.msgId() + ")";
        }
        op.record(t, TransferStatus.SETTLED, detail, "sys/" + t.system());
        log.info("{} settled {} {} → {} {}", t.system(), t.msgId(), t.payerBic(), t.payeeBic(), t.amount());
    }

    /** At the cut-off: every payment still queued for today (or earlier) is rejected; nothing carries over. */
    public int rejectAtCutoff(ClearingSystemView t2) {
        LocalDate today = op.today(t2);
        Instant cutoff = t2.cutoffTime().atDate(today).atZone(ZoneId.of(t2.timeZone())).toInstant();
        if (op.now().isBefore(cutoff)) {
            return 0;
        }
        List<TransferView> due = op.repo.queuedDueBy(today);
        op.repo.lockParticipants(due.stream().map(TransferView::payerBic).distinct().sorted().toList());
        int n = 0;
        for (TransferView t : op.repo.queuedDueBy(today)) {
            reject(t, "AM04", "Not settled by the " + t2.name() + " cut-off " + t2.cutoffTime() + " " + t2.timeZone()
                    + ": insufficient liquidity", "sys/" + t.system() + " end of day");
            n++;
        }
        return n;
    }

    void reject(TransferView t, String code, String detail, String actor) {
        op.repo.updateStatus(t.id(), TransferStatus.REJECTED, code, detail, null, op.now());
        String told = "";
        if (t.messageType().startsWith("pacs.008")) {
            told = " · pacs.002 RJCT " + op.status(t, t.payerBic(), TransactionStatus.RJCT, code, detail).msgId() + " → " + t.payerBic();
        }
        op.record(t, TransferStatus.REJECTED, code + " " + detail + told, actor);
    }

    /** Why each queued payment waits, for the participant's screen. */
    public Map<UUID, String> waiting(String payerBic, List<TransferView> queue) {
        if (queue.isEmpty()) {
            return Map.of();
        }
        ClearingSystemView t2 = op.system(RoutingMethod.RTGS, queue.get(0).amount().currencyCode());
        Money available = op.rtgsAccount(payerBic).available();
        return QueuePolicy.plan(items(queue), available.amount(), op.today(t2), available.currencyCode()).waiting();
    }

    private static List<QueuePolicy.Item> items(List<TransferView> queue) {
        return queue.stream().map(t -> new QueuePolicy.Item(t.id(), t.priority(), t.queueSeq(), t.amount().amount(),
                t.settlementDate())).toList();
    }
}
