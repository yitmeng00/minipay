package com.minipay.refdata.api;

import java.time.LocalDate;

/**
 * One routing rule.
 *
 * @param destination   {@code XXXXXXXX*} (an institution, any branch) or {@code *} (any institution)
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

    /** Does the rule's destination cover this institution? Branches do not matter: BIC8 is compared. */
    public boolean matches(String bic8) {
        return destination.equals("*") || destination.equals(bic8 + "*");
    }

    public boolean isCatchAll() {
        return destination.equals("*");
    }
}
