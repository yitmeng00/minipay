package com.minipay.settlement.internal.engine;

import com.minipay.ledger.api.EntryType;
import com.minipay.ledger.api.ReleaseReservationCommand;
import com.minipay.ledger.api.ReserveFundsCommand;
import com.minipay.ledger.api.SettleReservationCommand;
import com.minipay.ledger.api.SettlementLeg;
import com.minipay.messaging.api.TransactionStatus;
import com.minipay.settlement.api.TransferStatus;
import com.minipay.settlement.api.TransferView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * MiniPay Instant: like TIPS or RT1. Each participant prefunds a position (its instant account at the central
 * bank). A payment reserves the amount on the payer's position and goes to the payee, which must accept or
 * reject it within the timeout (10 s). Accepted in time: it settles on the positions at once, final, and both
 * banks are told. Rejected, or no answer in time: the reservation is released and both banks get a RJCT (AB05
 * for the timeout). The scheme never leaves either bank unsure for long.
 *
 * <p>All methods run inside the caller's transaction.
 */
@Component
public class InstantEngine {

    private static final Logger log = LoggerFactory.getLogger(InstantEngine.class);

    private final Operator op;

    InstantEngine(Operator op) {
        this.op = op;
    }

    /** The payer's position is locked and checked by the caller; this reserves it and sends the payment on. */
    public void reserveAndForward(TransferView t) {
        var hold = op.ledger.reserve(new ReserveFundsCommand("mpinst:" + t.id() + ":reserve", op.instantAccount(t.payerBic()).id(),
                t.amount(), t.id(), "Instant " + t.endToEndId() + " to " + t.payeeBic())).value();
        boolean simulated = op.simulated(t.payeeBic());
        Instant now = op.now();
        op.repo.reserved(t.id(), hold.id(), simulated ? now.plus(op.props.simulatedAnswerDelay()) : null, now);
        var fwd = op.forward(t);
        op.record(t, TransferStatus.PENDING, "Reserved " + t.amount() + " on " + t.payerBic() + "'s position · forwarded to "
                + t.payeeBic() + " (" + fwd.msgId() + "), answer due by " + t.deadline()
                + (simulated ? " (a participant the simulator plays: it answers by itself)" : ""), "sys/" + t.system());
    }

    /**
     * The payee's answer (or the simulator's, for a payee it plays). Late or repeated answers change nothing: a
     * payment that timed out stays rejected, whatever the payee says afterwards.
     */
    public TransferView answer(UUID transferId, boolean accepted, String reason, String info, String actor) {
        TransferView t = op.repo.lock(transferId).orElseThrow();
        if (t.status() != TransferStatus.PENDING) {
            return t;
        }
        Instant now = op.now();
        if (now.isAfter(t.deadline())) {
            timeout(t);
            return op.repo.find(transferId).orElseThrow();
        }
        UUID reservation = op.repo.reservationOf(t.id()).orElseThrow();
        if (accepted) {
            var entry = op.ledger.settle(new SettleReservationCommand("mpinst:" + t.id() + ":settle", reservation,
                    List.of(new SettlementLeg(op.instantAccount(t.payeeBic()).id(), t.amount(), EntryType.SETTLE,
                            "Instant " + t.endToEndId() + " from " + t.payerBic())), t.settlementDate())).value();
            op.repo.updateStatus(t.id(), TransferStatus.SETTLED, null, null, entry.settleEntryId(), now);
            op.status(t, t.payerBic(), TransactionStatus.ACSC, null, null);
            if (!op.simulated(t.payeeBic())) {
                op.status(t, t.payeeBic(), TransactionStatus.ACSC, null, null);
            }
            long ms = now.toEpochMilli() - t.receivedAt().toEpochMilli();
            op.record(t, TransferStatus.SETTLED, "Accepted by " + t.payeeBic() + " after " + ms + " ms · settled on the positions "
                    + "(final) · pacs.002 ACSC → " + t.payerBic() + " and " + t.payeeBic(), actor);
            log.info("{} settled {} in {} ms", t.system(), t.endToEndId(), ms);
        } else {
            op.ledger.release(new ReleaseReservationCommand("mpinst:" + t.id() + ":release", reservation, "Rejected by payee: " + reason));
            op.repo.updateStatus(t.id(), TransferStatus.REJECTED, reason, info, null, now);
            op.status(t, t.payerBic(), TransactionStatus.RJCT, reason, info);
            op.record(t, TransferStatus.REJECTED, "Rejected by " + t.payeeBic() + " (" + reason + (info == null ? "" : " " + info)
                    + ") · reservation released · pacs.002 RJCT → " + t.payerBic(), actor);
        }
        return op.repo.find(transferId).orElseThrow();
    }

    /** No answer in time: release the reservation, and tell both banks (AB05, timeout at the creditor agent). */
    public void timeout(TransferView t) {
        op.repo.reservationOf(t.id()).ifPresent(r -> op.ledger.release(new ReleaseReservationCommand("mpinst:" + t.id() + ":release",
                r, "Timeout: no answer from " + t.payeeBic())));
        String detail = "No answer from " + t.payeeBic() + " within the timeout";
        op.repo.updateStatus(t.id(), TransferStatus.REJECTED, "AB05", detail, null, op.now());
        op.status(t, t.payerBic(), TransactionStatus.RJCT, "AB05", detail);
        if (!op.simulated(t.payeeBic())) {
            op.status(t, t.payeeBic(), TransactionStatus.RJCT, "AB05", detail);
        }
        op.record(t, TransferStatus.REJECTED, "AB05 " + detail + " · reservation released · pacs.002 RJCT → " + t.payerBic()
                + " and " + t.payeeBic(), "sys/" + t.system() + " timer");
        log.info("{} timed out {} (no answer from {})", t.system(), t.endToEndId(), t.payeeBic());
    }

    /** Called by the timer: time out what is overdue, answer for the participants the simulator plays. */
    public void due(UUID transferId) {
        TransferView t = op.repo.lock(transferId).orElse(null);
        if (t == null || t.status() != TransferStatus.PENDING) {
            return;
        }
        if (!op.now().isBefore(t.deadline())) {
            timeout(t);
        } else {
            answer(t.id(), true, null, null, "sim/" + t.payeeBic());
        }
    }
}
