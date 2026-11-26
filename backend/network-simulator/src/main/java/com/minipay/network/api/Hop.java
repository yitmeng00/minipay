package com.minipay.network.api;

import com.minipay.common.money.Money;
import java.time.Instant;

/**
 * One leg of a payment's journey: a message from one bank to the next, and the answer it got.
 *
 * @param amount the interbank amount on this leg (after the deductions of the banks before it)
 * @param reply  the receiving bank's status for this leg (ACSP, ACCP, ACSC or RJCT), null if none yet
 */
public record Hop(
        int number,
        String type,
        String fromBic,
        String toBic,
        String msgId,
        Money amount,
        String settlementMethod,
        Instant sentAt,
        String reply,
        String replyReason,
        Instant replyAt) {
}
