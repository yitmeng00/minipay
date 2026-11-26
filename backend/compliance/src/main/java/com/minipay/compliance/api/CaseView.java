package com.minipay.compliance.api;

import com.minipay.common.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A held payment and everything an analyst needs to decide it.
 *
 * @param fourEyes    a release needs a second approver (best score at or above the four-eyes threshold)
 * @param slaDeadline when the decision is due (held at + SLA)
 */
public record CaseView(
        UUID id,
        UUID paymentId,
        UUID uetr,
        String direction,
        String kind,
        Money amount,
        String debtorName,
        String creditorName,
        String route,
        CaseStatus status,
        int bestScore,
        boolean fourEyes,
        Instant heldAt,
        Instant slaDeadline,
        Instant closedAt,
        LocalDate screenedAsOf,
        String lists,
        List<HitView> hits,
        List<DecisionView> decisions) {
}
