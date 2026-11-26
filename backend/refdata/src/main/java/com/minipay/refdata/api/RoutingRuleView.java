package com.minipay.refdata.api;

import java.time.LocalDate;

/**
 * One routing rule.
 *
 * @param destination   a full BIC11 (one branch), {@code XXXXXXXX*} (an institution, any branch) or
 *                      {@code *} (any institution); the most specific matching rule wins a priority tie
 * @param nextHopBic    the correspondent for SERIAL and COVER; null for DIRECT and RTGS
 * @param settlementAccount the ledger account number we credit when we send
 * @param priority      lowest number wins among matching rules valid on the value date
 * @param effectiveTo   last day the rule applies (inclusive), or null if open-ended
 */
public record RoutingRuleView(
        String ruleId,
        String destination,
        String currency,
        RoutingMethod method,
        String nextHopBic,
        String settlementAccount,
        int priority,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String releaseVersion) {

    public boolean validOn(LocalDate date) {
        return !date.isBefore(effectiveFrom) && (effectiveTo == null || !date.isAfter(effectiveTo));
    }

    /** Does the rule's destination cover this creditor agent (BIC11)? */
    public boolean matches(String bic11) {
        return destination.equals("*") || destination.equals(bic11) || destination.equals(bic11.substring(0, 8) + "*");
    }

    /** 2 = one branch, 1 = an institution, 0 = anyone. */
    public int specificity() {
        return destination.equals("*") ? 0 : destination.endsWith("*") ? 1 : 2;
    }

    public boolean isCatchAll() {
        return destination.equals("*");
    }
}
