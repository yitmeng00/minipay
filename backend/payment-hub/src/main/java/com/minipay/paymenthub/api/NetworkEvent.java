package com.minipay.paymenthub.api;

import com.minipay.common.money.Money;
import java.util.Objects;

/**
 * Something the network told us about a payment we sent: read from a received pacs.002 / pacs.004, or,
 * for a bank the simulator does not run, injected by the ops console playing that bank.
 *
 * @param reasonCode     ISO reason, required for RETURNED (e.g. AC04 ClosedAccountNumber)
 * @param returnedAmount for RETURNED: amount that came back (may be less than sent: banks on the route
 *                       deduct their charges); null = the full interbank settlement amount
 */
public record NetworkEvent(Type type, String reasonCode, Money returnedAmount, String reference) {

    public enum Type {
        /** pacs.002 ACCP/ACSP from the next agent. */
        ACCEPTED,
        /** Settlement confirmed (e.g. gpi tracker "credited", or RTGS confirmation). */
        SETTLED,
        /** pacs.002 RJCT from a bank further on: it refused the payment (our booking is reversed). */
        REJECTED,
        /** pacs.004 payment return. */
        RETURNED
    }

    public NetworkEvent {
        Objects.requireNonNull(type, "type");
        if ((type == Type.RETURNED || type == Type.REJECTED) && (reasonCode == null || !reasonCode.matches("[A-Z0-9]{4}"))) {
            throw new IllegalArgumentException("A return or rejection needs a 4-character ISO reason code, e.g. AC04");
        }
    }
}
