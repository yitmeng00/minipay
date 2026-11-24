package com.minipay.paymenthub.internal.domain;

import static com.minipay.paymenthub.api.PaymentStatus.ACKED;
import static com.minipay.paymenthub.api.PaymentStatus.FUNDS_RESERVED;
import static com.minipay.paymenthub.api.PaymentStatus.HELD_COMPLIANCE;
import static com.minipay.paymenthub.api.PaymentStatus.RECEIVED;
import static com.minipay.paymenthub.api.PaymentStatus.REJECTED;
import static com.minipay.paymenthub.api.PaymentStatus.RETURNED;
import static com.minipay.paymenthub.api.PaymentStatus.SCREENED;
import static com.minipay.paymenthub.api.PaymentStatus.SENT;
import static com.minipay.paymenthub.api.PaymentStatus.SETTLED;
import static com.minipay.paymenthub.api.PaymentStatus.VALIDATED;

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
 * The table below is small enough to read in one go — and is tested pair by pair.
 *
 * <ul>
 *   <li>REJECTED is only possible <em>before</em> SENT: once a pacs.008 has left the bank the payment
 *       can no longer be "rejected" by us; it can only come back as a RETURN (pacs.004).</li>
 *   <li>HELD_COMPLIANCE is entered from SCREENED (a screening hit) and left by a compliance decision:
 *       release (→ FUNDS_RESERVED) or refuse (→ REJECTED).</li>
 *   <li>RETURNED is only possible after SETTLED: funds that never moved cannot be returned.</li>
 * </ul>
 */
public final class PaymentStateMachine {

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = new EnumMap<>(PaymentStatus.class);

    static {
        ALLOWED.put(RECEIVED, EnumSet.of(VALIDATED, REJECTED));
        ALLOWED.put(VALIDATED, EnumSet.of(SCREENED, REJECTED));
        ALLOWED.put(SCREENED, EnumSet.of(FUNDS_RESERVED, HELD_COMPLIANCE, REJECTED));
        ALLOWED.put(HELD_COMPLIANCE, EnumSet.of(FUNDS_RESERVED, REJECTED));
        ALLOWED.put(FUNDS_RESERVED, EnumSet.of(SENT, REJECTED));
        ALLOWED.put(SENT, EnumSet.of(ACKED));
        ALLOWED.put(ACKED, EnumSet.of(SETTLED));
        ALLOWED.put(SETTLED, EnumSet.of(RETURNED));
        ALLOWED.put(REJECTED, EnumSet.noneOf(PaymentStatus.class));
        ALLOWED.put(RETURNED, EnumSet.noneOf(PaymentStatus.class));
    }

    private PaymentStateMachine() {
    }

    public static boolean canTransition(PaymentStatus from, PaymentStatus to) {
        return ALLOWED.get(from).contains(to);
    }

    public static Set<PaymentStatus> allowedFrom(PaymentStatus from) {
        return Set.copyOf(ALLOWED.get(from));
    }

    public static void check(PaymentStatus from, PaymentStatus to) {
        if (!canTransition(from, to)) {
            throw new PaymentException(PaymentError.ILLEGAL_TRANSITION,
                    "Illegal payment transition " + from + " -> " + to + " (allowed: " + ALLOWED.get(from) + ")");
        }
    }
}
