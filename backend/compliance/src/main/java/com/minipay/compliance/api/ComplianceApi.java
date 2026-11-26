package com.minipay.compliance.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Sanctions screening and the decisions on held payments. */
public interface ComplianceApi {

    /** Screens a payment once; a repeat for the same payment returns the stored outcome. */
    ScreeningOutcome screen(ScreeningRequest request);

    /** Where the decision on a held payment stands. */
    CaseVerdict verdict(UUID paymentId);

    /** Records a decision (Idempotency-Key: a replay returns the same result, a different request is refused). */
    DecisionResult decide(DecideCommand command);

    /** @param open true = cases waiting for a decision; false = closed on the given day */
    List<CaseView> cases(boolean open, String listCode, LocalDate closedOn);

    CaseView getCase(UUID caseId);

    Optional<ScreeningView> screeningForPayment(UUID paymentId);

    Optional<CaseView> caseForPayment(UUID paymentId);

    ComplianceStats stats();

    ComplianceSettings settings();

    List<SanctionsListView> lists(LocalDate asOf);

    List<SanctionsEntryView> entries(String listCode, String query, LocalDate asOf);

    /** Every decision taken on the given days, as CSV, for the audit file. */
    String decisionsCsv(LocalDate from, LocalDate to);
}
