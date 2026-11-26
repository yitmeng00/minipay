package com.minipay.compliance.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** How a payment was screened, hit or not. @param caseId null when the payment was clear */
public record ScreeningView(UUID id, UUID paymentId, Instant screenedAt, LocalDate asOf, String lists, int screenedItems,
                            int holdThreshold, int bestScore, String outcome, UUID caseId, CaseStatus caseStatus) {
}
