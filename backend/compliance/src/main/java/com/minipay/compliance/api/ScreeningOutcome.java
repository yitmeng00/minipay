package com.minipay.compliance.api;

import java.util.UUID;

/**
 * @param hit       true if any score reached the hold threshold: the payment must wait for a decision
 * @param caseId    the case opened for the hits, if any
 * @param bestScore the highest score of any name or BIC against any entry (also when below threshold)
 * @param detail    one line for the payment's history
 */
public record ScreeningOutcome(UUID screeningId, boolean hit, int bestScore, UUID caseId, boolean fourEyes, String detail) {
}
