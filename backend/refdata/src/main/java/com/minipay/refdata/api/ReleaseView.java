package com.minipay.refdata.api;

import java.time.Instant;

/**
 * A published release of reference data.
 *
 * @param routingRulesChanged how many routing rules this release introduced
 */
public record ReleaseView(
        String version,
        Instant publishedAt,
        String publishedBy,
        String approvedBy,
        String description,
        int routingRulesChanged) {
}
