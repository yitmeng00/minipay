package com.minipay.compliance.internal.web;

import com.minipay.compliance.api.CaseView;
import com.minipay.compliance.api.ComplianceApi;
import com.minipay.compliance.api.ComplianceError;
import com.minipay.compliance.api.ComplianceException;
import com.minipay.compliance.api.ComplianceSettings;
import com.minipay.compliance.api.ComplianceStats;
import com.minipay.compliance.api.DecideCommand;
import com.minipay.compliance.api.DecisionAction;
import com.minipay.compliance.api.DecisionReason;
import com.minipay.compliance.api.DecisionResult;
import com.minipay.compliance.api.SanctionsEntryView;
import com.minipay.compliance.api.SanctionsListView;
import com.minipay.compliance.api.ScreeningView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/compliance")
@Tag(name = "Compliance", description = "Sanctions screening, the queue of held payments, and four-eyes decisions")
class ComplianceController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";

    private final ComplianceApi compliance;

    ComplianceController(ComplianceApi compliance) {
        this.compliance = compliance;
    }

    @GetMapping("/cases")
    @Operation(summary = "Held payments: state=open (waiting for a decision, oldest first) or state=closed (closed on a day)")
    List<CaseView> cases(@RequestParam(defaultValue = "open") String state, @RequestParam(required = false) String list,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (!state.equals("open") && !state.equals("closed")) {
            throw new IllegalArgumentException("state is open or closed");
        }
        return compliance.cases(state.equals("open"), list == null || list.isBlank() ? null : list, date);
    }

    @GetMapping("/cases/{id}")
    CaseView getCase(@PathVariable UUID id) {
        return compliance.getCase(id);
    }

    record DecisionRequest(DecisionAction action, DecisionReason reason, String comment, String decidedBy) {
    }

    @PostMapping("/cases/{id}/decisions")
    @Operation(summary = "Decide a held payment: RELEASE, REJECT, ESCALATE; APPROVE or DECLINE someone else's release; "
            + "WITHDRAW your own release while it waits for approval")
    ResponseEntity<CaseView> decide(@RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key, @PathVariable UUID id,
                                    @RequestBody DecisionRequest r) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }
        DecisionResult result = compliance.decide(new DecideCommand(key, id, r.action(), r.reason(), r.comment(), r.decidedBy()));
        return result.replayed()
                ? ResponseEntity.ok().header(REPLAYED, "true").body(result.caseView())
                : ResponseEntity.status(HttpStatus.CREATED).body(result.caseView());
    }

    record PaymentCompliance(ScreeningView screening, CaseView caseView) {
    }

    @GetMapping("/payments/{paymentId}")
    @Operation(summary = "How a payment was screened, and its case if it was held")
    PaymentCompliance forPayment(@PathVariable UUID paymentId) {
        ScreeningView s = compliance.screeningForPayment(paymentId)
                .orElseThrow(() -> new ComplianceException(ComplianceError.NOT_FOUND, "Payment " + paymentId + " was not screened"));
        return new PaymentCompliance(s, compliance.caseForPayment(paymentId).orElse(null));
    }

    @GetMapping("/stats")
    ComplianceStats stats() {
        return compliance.stats();
    }

    @GetMapping("/settings")
    ComplianceSettings settings() {
        return compliance.settings();
    }

    @GetMapping("/lists")
    List<SanctionsListView> lists(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return compliance.lists(asOf);
    }

    @GetMapping("/entries")
    @Operation(summary = "List entries; with asOf only those in force that day, without it every version including delisted ones")
    List<SanctionsEntryView> entries(@RequestParam(required = false) String list, @RequestParam(required = false) String q,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return compliance.entries(list, q, asOf);
    }

    @GetMapping(value = "/decisions.csv", produces = "text/csv")
    @Operation(summary = "Every decision taken from one day to another (inclusive), for the audit file")
    ResponseEntity<String> decisionsCsv(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to != null ? to : from;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"compliance-decisions-" + from + "_" + end + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(compliance.decisionsCsv(from, end));
    }
}
