package com.minipay.refdata.api;

import java.time.LocalDate;

/**
 * Publish a new routing rule as its own release.
 *
 * <p>Rules are never edited. To change a route, publish a new rule and name the one it
 * {@code supersedes}: the old rule is end-dated the day before the new one starts, in the same
 * transaction, so there is no gap and no overlap.
 *
 * @param effectiveTo last day (inclusive), or null for open-ended
 * @param supersedes  rule id to end-date, or null
 * @param publishedBy who made the change
 * @param approvedBy  who approved it; must be someone else (four-eyes principle)
 */
public record CreateRoutingRuleCommand(
        String idempotencyKey,
        String destination,
        String currency,
        RoutingMethod method,
        String nextHopBic,
        String settlementAccount,
        int priority,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String supersedes,
        String publishedBy,
        String approvedBy,
        String description) {
}
