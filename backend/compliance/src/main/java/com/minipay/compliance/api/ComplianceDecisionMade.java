package com.minipay.compliance.api;

import java.util.UUID;

/**
 * Published in-process after a decision that closes a case has been committed, so the payment hub can
 * resume the payment straight away. If the event is lost (crash after commit), the hub's recovery
 * sweeper finds the payment anyway: the committed decision, not the event, is the source of truth.
 */
public record ComplianceDecisionMade(UUID caseId, UUID paymentId, CaseStatus status) {
}
