package com.minipay.recon.internal.web;

import com.minipay.recon.api.ReconApi;
import com.minipay.recon.api.ReconViews;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recon")
@Tag(name = "Reconciliation", description = "Nostro reconciliation against the servicers' camt.053 statements (M8)")
class ReconController {

    private final ReconApi recon;

    ReconController(ReconApi recon) {
        this.recon = recon;
    }

    @GetMapping("/nostros")
    List<ReconViews.Nostro> nostros() {
        return recon.nostros();
    }

    @GetMapping("/nostros/{account}")
    @Operation(summary = "One nostro on one statement date (default: the latest)")
    ReconViews.Reconciliation reconciliation(@PathVariable String account,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return recon.reconciliation(account, date);
    }

    @PostMapping("/nostros/{account}/rerun")
    ReconViews.Reconciliation rerun(@RequestHeader("Idempotency-Key") String key, @PathVariable String account) {
        return recon.rerun(key, account);
    }

    @PostMapping("/matches")
    @Operation(summary = "Match ledger lines and statement entries by hand (their amounts must add up)")
    ReconViews.Reconciliation match(@RequestHeader("Idempotency-Key") String key, @RequestBody ReconViews.ManualMatch body) {
        return recon.manualMatch(key, body);
    }

    @PostMapping("/breaks/{id}/resolve")
    @Operation(summary = "Resolve a break: CARRY_FORWARD, ADJUSTMENT (books the difference) or CLAIM_RAISED")
    ReconViews.BreakView resolve(@RequestHeader("Idempotency-Key") String key, @PathVariable String id,
                                 @RequestBody ReconViews.Resolve body) {
        return recon.resolve(key, id, body);
    }

    record Reopen(String by) {
    }

    @PostMapping("/breaks/{id}/reopen")
    ReconViews.BreakView reopen(@RequestHeader("Idempotency-Key") String key, @PathVariable String id, @RequestBody Reopen body) {
        return recon.reopen(key, id, body.by());
    }

    record Publish(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    }

    @PostMapping("/statements/publish")
    @Operation(summary = "As servicer: send the camt.053 of a date for every account we keep for a bank on the network")
    ReconViews.Published publish(@RequestHeader("Idempotency-Key") String key, @RequestBody(required = false) Publish body) {
        return recon.publishStatements(key, body == null ? null : body.date());
    }
}
