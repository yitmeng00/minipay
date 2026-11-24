package com.minipay.paymenthub.api;

import com.minipay.common.money.Money;
import java.util.Objects;

/**
 * Something the network told us about a payment we sent. In M5 these come from real pacs.002 /
 * pacs.004 messages via the network simulator; until then the ops console can inject them.
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
        /** pacs.004 payment return. */
        RETURNED
    }

    public NetworkEvent {
        Objects.requireNonNull(type, "type");
        if (type == Type.RETURNED && (reasonCode == null || !reasonCode.matches("[A-Z0-9]{4}"))) {
            throw new IllegalArgumentException("A return needs a 4-character ISO reason code, e.g. AC04");
        }
    }
}
