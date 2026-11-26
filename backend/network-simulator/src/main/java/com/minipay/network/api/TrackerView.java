package com.minipay.network.api;

import com.minipay.common.money.Money;
import com.minipay.messaging.api.Charge;
import java.time.Duration;
import java.util.List;

/**
 * Everything the network knows about one payment, end to end (what the gpi Tracker shows).
 *
 * @param status        ACSP (on its way), ACSC (credited to the creditor), RJCT (rejected) or RTND (returned)
 * @param hops          the pacs.008 legs, debtor's bank first
 * @param coverLegs     for a cover payment: the pacs.009 to the correspondent and its camt.054 to the creditor's bank
 * @param returnPath    pacs.004 legs, back towards the debtor's bank
 * @param deductions    every bank's charge, in route order
 * @param creditedAmount what reached the creditor (known once the creditor's bank confirmed with ACSC)
 * @param endToEnd      from the first pacs.008 to the creditor's bank's confirmation
 */
public record TrackerView(
        String uetr,
        String status,
        String method,
        String debtorAgentBic,
        String creditorAgentBic,
        Money sentAmount,
        Money creditedAmount,
        Money returnedAmount,
        List<Charge> deductions,
        List<Hop> hops,
        List<Hop> coverLegs,
        List<Hop> returnPath,
        List<TrackedMessage> messages,
        Duration endToEnd) {
}
