package com.minipay.compliance.api;

import java.util.UUID;

/**
 * @param reason    required for RELEASE (FALSE_POSITIVE), REJECT (TRUE_MATCH), ESCALATE (INSUFFICIENT_INFORMATION)
 * @param comment   20–500 characters; goes into the audit trail with the decision
 * @param decidedBy the officer's user id, e.g. {@code n.rahman}
 */
public record DecideCommand(String idempotencyKey, UUID caseId, DecisionAction action, DecisionReason reason,
                            String comment, String decidedBy) {
}
