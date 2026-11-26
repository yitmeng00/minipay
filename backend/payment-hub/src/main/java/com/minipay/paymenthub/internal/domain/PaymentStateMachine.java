package com.minipay.paymenthub.internal.domain;

import static com.minipay.paymenthub.api.PaymentStatus.ACKED;
import static com.minipay.paymenthub.api.PaymentStatus.AWAITING_COVER;
import static com.minipay.paymenthub.api.PaymentStatus.FUNDS_RESERVED;
import static com.minipay.paymenthub.api.PaymentStatus.HELD_COMPLIANCE;
import static com.minipay.paymenthub.api.PaymentStatus.RECEIVED;
import static com.minipay.paymenthub.api.PaymentStatus.REJECTED;
import static com.minipay.paymenthub.api.PaymentStatus.RETURNED;
import static com.minipay.paymenthub.api.PaymentStatus.SCREENED;
import static com.minipay.paymenthub.api.PaymentStatus.SENT;
import static com.minipay.paymenthub.api.PaymentStatus.SETTLED;
import static com.minipay.paymenthub.api.PaymentStatus.VALIDATED;

import com.minipay.paymenthub.api.PaymentDirection;
import com.minipay.paymenthub.api.PaymentError;
import com.minipay.paymenthub.api.PaymentException;
import com.minipay.paymenthub.api.PaymentStatus;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The only place that decides which status changes are legal. Every transition in the code base goes
 * through {@link #check}; an illegal one throws, it is never silently ignored.
 *
 * <p>Why so strict? Status drives money. A payment that jumps from RECEIVED to SENT would skip the
 * funds reservation; a REJECTED payment that becomes SETTLED would be paid without being booked.
 * The tables below are small enough to read in one go, and are tested pair by pair.
 *
 * <h2>Outgoing (we are the debtor's bank)</h2>
 * <ul>
 *   <li>HELD_COMPLIANCE is entered from SCREENED (a screening hit) and left by a compliance decision.</li>
 *   <li>After SENT the next banks decide: an ACSP/ACCP acknowledges (ACKED), the creditor's bank's
 *       ACSC confirms (SETTLED, possibly without an acknowledgement first), a RJCT from a bank further
 *       on rejects (REJECTED: our booking is reversed), and a pacs.004 returns the money (RETURNED).
 *       A return can come before any confirmation: with INDA/INGA settlement the money moved when we
 *       sent, so the creditor's bank may send it back straight away.</li>
 * </ul>
 *
 * <h2>Incoming (another bank sent it to us)</h2>
 * <ul>
 *   <li>As <b>creditor's bank</b>: SCREENED → SETTLED when we credit our customer, or → AWAITING_COVER
 *       when the money comes separately (cover method) and we wait for it.</li>
 *   <li>As <b>intermediary</b>: SCREENED → SENT when we forward it, then the same as outgoing.</li>
 *   <li>REJECTED before we booked anything (pacs.002 RJCT back); RETURNED after (pacs.004 back).</li>
 * </ul>
 */
public final class PaymentStateMachine {

    private static final Map<PaymentStatus, Set<PaymentStatus>> OUTGOING = new EnumMap<>(PaymentStatus.class);
    private static final Map<PaymentStatus, Set<PaymentStatus>> INCOMING = new EnumMap<>(PaymentStatus.class);

    static {
        OUTGOING.put(RECEIVED, EnumSet.of(VALIDATED, REJECTED));
        OUTGOING.put(VALIDATED, EnumSet.of(SCREENED, REJECTED));
        OUTGOING.put(SCREENED, EnumSet.of(FUNDS_RESERVED, HELD_COMPLIANCE, REJECTED));
        OUTGOING.put(HELD_COMPLIANCE, EnumSet.of(FUNDS_RESERVED, REJECTED));
        OUTGOING.put(FUNDS_RESERVED, EnumSet.of(SENT, REJECTED));
        OUTGOING.put(AWAITING_COVER, EnumSet.noneOf(PaymentStatus.class));
        OUTGOING.put(SENT, EnumSet.of(ACKED, SETTLED, REJECTED, RETURNED));
        OUTGOING.put(ACKED, EnumSet.of(SETTLED, REJECTED, RETURNED));
        OUTGOING.put(SETTLED, EnumSet.of(RETURNED));
        OUTGOING.put(REJECTED, EnumSet.noneOf(PaymentStatus.class));
        OUTGOING.put(RETURNED, EnumSet.noneOf(PaymentStatus.class));

        INCOMING.put(RECEIVED, EnumSet.of(VALIDATED, REJECTED));
        INCOMING.put(VALIDATED, EnumSet.of(SCREENED, REJECTED));
        INCOMING.put(SCREENED, EnumSet.of(SETTLED, SENT, AWAITING_COVER, HELD_COMPLIANCE, REJECTED));
        INCOMING.put(HELD_COMPLIANCE, EnumSet.of(REJECTED)); // the release path arrives with compliance (M6)
        INCOMING.put(FUNDS_RESERVED, EnumSet.noneOf(PaymentStatus.class));
        INCOMING.put(AWAITING_COVER, EnumSet.of(SETTLED, REJECTED));
        INCOMING.put(SENT, EnumSet.of(ACKED, SETTLED, REJECTED, RETURNED));
        INCOMING.put(ACKED, EnumSet.of(SETTLED, REJECTED, RETURNED));
        INCOMING.put(SETTLED, EnumSet.of(RETURNED));
        INCOMING.put(REJECTED, EnumSet.noneOf(PaymentStatus.class));
        INCOMING.put(RETURNED, EnumSet.noneOf(PaymentStatus.class));
    }

    private PaymentStateMachine() {
    }

    private static Map<PaymentStatus, Set<PaymentStatus>> table(PaymentDirection direction) {
        return direction == PaymentDirection.INCOMING ? INCOMING : OUTGOING;
    }

    public static boolean canTransition(PaymentDirection direction, PaymentStatus from, PaymentStatus to) {
        return table(direction).get(from).contains(to);
    }

    public static Set<PaymentStatus> allowedFrom(PaymentDirection direction, PaymentStatus from) {
        return Set.copyOf(table(direction).get(from));
    }

    public static void check(PaymentDirection direction, PaymentStatus from, PaymentStatus to) {
        if (!canTransition(direction, from, to)) {
            throw new PaymentException(PaymentError.ILLEGAL_TRANSITION, "Illegal " + direction.name().toLowerCase()
                    + " payment transition " + from + " -> " + to + " (allowed: " + table(direction).get(from) + ")");
        }
    }
}
