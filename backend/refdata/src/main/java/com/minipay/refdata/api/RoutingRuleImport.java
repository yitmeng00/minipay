package com.minipay.refdata.api;

import java.time.LocalDate;

/**
 * A routing rule loaded from a reference data file, with its id, dates and release given. Used to seed
 * a bank's routing table; importing the same rule again is a no-op, importing a different rule
 * under an existing id fails.
 */
public record RoutingRuleImport(
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
}
