package com.minipay.compliance.api;

/** @param replayed the Idempotency-Key was seen before with the same request: nothing new happened */
public record DecisionResult(CaseView caseView, boolean replayed) {
}
