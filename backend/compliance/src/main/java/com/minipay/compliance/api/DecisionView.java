package com.minipay.compliance.api;

import java.time.Instant;

public record DecisionView(long id, DecisionAction action, DecisionReason reason, String comment, String decidedBy,
                           Instant decidedAt, CaseStatus statusBefore, CaseStatus statusAfter) {
}
