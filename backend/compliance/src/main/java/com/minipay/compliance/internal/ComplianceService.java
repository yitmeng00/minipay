package com.minipay.compliance.internal;

import com.minipay.common.money.Money;
import com.minipay.compliance.api.CaseStatus;
import com.minipay.compliance.api.CaseVerdict;
import com.minipay.compliance.api.CaseView;
import com.minipay.compliance.api.ComplianceApi;
import com.minipay.compliance.api.ComplianceDecisionMade;
import com.minipay.compliance.api.ComplianceError;
import com.minipay.compliance.api.ComplianceException;
import com.minipay.compliance.api.ComplianceSettings;
import com.minipay.compliance.api.ComplianceStats;
import com.minipay.compliance.api.DecideCommand;
import com.minipay.compliance.api.DecisionAction;
import com.minipay.compliance.api.DecisionReason;
import com.minipay.compliance.api.DecisionResult;
import com.minipay.compliance.api.DecisionView;
import com.minipay.compliance.api.HitView;
import com.minipay.compliance.api.SanctionsEntryView;
import com.minipay.compliance.api.SanctionsListView;
import com.minipay.compliance.api.ScreeningOutcome;
import com.minipay.compliance.api.ScreeningRequest;
import com.minipay.compliance.api.ScreeningView;
import com.minipay.compliance.internal.config.ComplianceProperties;
import com.minipay.compliance.internal.engine.Screener;
import com.minipay.compliance.internal.persistence.ComplianceRepository;
import com.minipay.compliance.internal.persistence.ComplianceRepository.CaseRow;
import com.minipay.compliance.internal.persistence.ComplianceRepository.ScreeningRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Screening and decisions (ADR-007).
 *
 * <p><b>Screening</b> is written once per payment. The pipeline may run the screening step again after a
 * crash, or two workers may race on it; both get the first screening back, so a payment is never held
 * twice, nor cleared by a second run against a different list.
 *
 * <p><b>Decisions</b> lock the case row, check the decision is possible in the case's current status,
 * and append the decision with the new status in one transaction. A closing decision is announced with
 * {@link ComplianceDecisionMade} <i>after</i> the commit, outside any transaction, so the payment hub
 * resumes the payment in its own transactions and the ledger is never joined to ours.
 */
@Service
public class ComplianceService implements ComplianceApi {

    private static final Logger log = LoggerFactory.getLogger(ComplianceService.class);

    private final ComplianceRepository repo;
    private final ComplianceProperties props;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final ZoneId bankZone;

    public ComplianceService(ComplianceRepository repo, ComplianceProperties props, TransactionTemplate tx,
                             ApplicationEventPublisher events, ObjectProvider<Clock> clock,
                             @Value("${minipay.bank.time-zone:UTC}") ZoneId bankZone) {
        this.repo = repo;
        this.props = props;
        this.tx = tx;
        this.events = events;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
        this.bankZone = bankZone;
    }

    // ------------------------------------------------------------------ screening

    @Override
    public ScreeningOutcome screen(ScreeningRequest r) {
        Optional<ScreeningRow> existing = repo.findScreeningByPayment(r.paymentId());
        if (existing.isPresent()) {
            return outcome(existing.get());
        }
        Screener.Result result = Screener.screen(r.parties(), repo.activeEntries(r.asOf(), props.lists()), props.holdThreshold());
        boolean hit = !result.hits().isEmpty();
        int best = hit ? result.hits().get(0).score() : result.bestScore();
        Instant now = clock.instant();
        ScreeningRow row = new ScreeningRow(UUID.randomUUID(), r.paymentId(), r.uetr(), r.direction(), r.kind(),
                r.amount().amount(), r.amount().currencyCode(), r.debtorName(), r.creditorName(), r.route(), r.asOf(),
                repo.snapshots(r.asOf(), props.lists()), r.parties().size(), props.holdThreshold(), best,
                hit ? "HIT" : "CLEAR", now);
        Boolean written = tx.execute(status -> {
            if (!repo.insertScreening(row, r.parties())) {
                return false; // another run screened it first: theirs is the record
            }
            for (Screener.Hit h : result.hits()) {
                repo.insertHit(row.id(), h.party().role(), h.party().field(), partyName(h), h.entry().pk(), h.matchedOn(),
                        h.matchedName(), h.score(), new ComplianceRepository.HitDetail(h.party().account(), h.party().bic(),
                                h.partyTokens(), h.listedTokens(), h.factors(), h.comparison(), h.hint()));
            }
            if (hit) {
                repo.insertCase(new CaseRow(UUID.randomUUID(), row.id(), r.paymentId(), best,
                        best >= props.fourEyesThreshold(), CaseStatus.OPEN, now, null));
            }
            return true;
        });
        ScreeningRow stored = Boolean.TRUE.equals(written) ? row : repo.findScreeningByPayment(r.paymentId()).orElseThrow();
        if (hit && Boolean.TRUE.equals(written)) {
            log.info("Payment {} held for compliance: best score {}", r.paymentId(), best);
        }
        return outcome(stored);
    }

    private static String partyName(Screener.Hit h) {
        return h.party().name() != null ? h.party().name() : h.party().bic();
    }

    private ScreeningOutcome outcome(ScreeningRow s) {
        String as = " against " + s.lists() + " (as of " + s.asOf() + ")";
        if ("CLEAR".equals(s.outcome())) {
            return new ScreeningOutcome(s.id(), false, s.bestScore(), null, false, "Screened " + s.screenedItems()
                    + " names and BICs" + as + ": no match ≥ " + s.holdThreshold() + " (best " + s.bestScore() + ")");
        }
        CaseRow c = repo.findCaseByPayment(s.paymentId()).orElseThrow();
        List<HitView> hits = repo.hits(s.id());
        HitView top = hits.get(0);
        String detail = "Held for review: " + top.field() + " “" + top.partyName() + "” ≈ " + top.matchedName()
                + " (" + top.entryId() + ", " + top.listName() + ") score " + top.score()
                + (hits.size() > 1 ? ", +" + (hits.size() - 1) + " more hit" + (hits.size() > 2 ? "s" : "") : "")
                + " · screened" + as + (c.fourEyes() ? " · release needs four eyes" : "");
        return new ScreeningOutcome(s.id(), true, s.bestScore(), c.id(), c.fourEyes(), detail);
    }

    @Override
    public CaseVerdict verdict(UUID paymentId) {
        Optional<CaseRow> c = repo.findCaseByPayment(paymentId);
        if (c.isEmpty()) {
            return CaseVerdict.none();
        }
        if (!c.get().status().isClosed()) {
            return new CaseVerdict(CaseVerdict.Verdict.PENDING, null, null);
        }
        List<DecisionView> decisions = repo.decisions(c.get().id());
        DecisionView last = decisions.get(decisions.size() - 1);
        if (c.get().status() == CaseStatus.REJECTED) {
            return new CaseVerdict(CaseVerdict.Verdict.REJECTED, "Compliance: rejected by " + last.decidedBy() + " (true match): "
                    + quote(last.comment()), last.decidedBy());
        }
        if (last.action() == DecisionAction.APPROVE) {
            DecisionView release = decisions.stream().filter(d -> d.action() == DecisionAction.RELEASE).reduce((a, b) -> b).orElseThrow();
            return new CaseVerdict(CaseVerdict.Verdict.RELEASED, "Compliance: released by " + release.decidedBy() + ", approved by "
                    + last.decidedBy() + " (false positive): " + quote(release.comment()), release.decidedBy() + "+" + last.decidedBy());
        }
        return new CaseVerdict(CaseVerdict.Verdict.RELEASED, "Compliance: released by " + last.decidedBy() + " (false positive): "
                + quote(last.comment()), last.decidedBy());
    }

    private static String quote(String comment) {
        return "“" + (comment.length() > 120 ? comment.substring(0, 117) + "…" : comment) + "”";
    }

    // ------------------------------------------------------------------ decisions

    @Override
    public DecisionResult decide(DecideCommand c) {
        validate(c);
        String hash = fingerprint(c);
        Outcome outcome = tx.execute(status -> {
            repo.lockIdempotencyKey(c.idempotencyKey());
            Optional<ComplianceRepository.StoredKey> stored = repo.findDecisionKey(c.idempotencyKey());
            if (stored.isPresent()) {
                if (!stored.get().requestHash().equals(hash)) {
                    throw new ComplianceException(ComplianceError.IDEMPOTENCY_CONFLICT,
                            "Idempotency-Key " + c.idempotencyKey() + " was already used for a different decision");
                }
                return new Outcome(stored.get().caseId(), null, true);
            }
            CaseRow row = repo.lockCase(c.caseId()).orElseThrow(() -> notFound(c.caseId()));
            CaseStatus next = next(row, c);
            Instant now = clock.instant();
            repo.insertDecision(row.id(), c.action(), c.reason(), c.comment().trim(), c.decidedBy(), now, row.status(), next,
                    c.idempotencyKey(), hash);
            repo.updateCaseStatus(row.id(), next, next.isClosed() ? now : null);
            return new Outcome(row.id(), next, false);
        });
        CaseView view = getCase(outcome.caseId());
        if (!outcome.replayed()) {
            log.info("Case {} {} by {} → {}", c.caseId(), c.action(), c.decidedBy(), outcome.next());
            if (outcome.next().isClosed()) {
                events.publishEvent(new ComplianceDecisionMade(view.id(), view.paymentId(), outcome.next()));
            }
        }
        return new DecisionResult(view, outcome.replayed());
    }

    private record Outcome(UUID caseId, CaseStatus next, boolean replayed) {
    }

    /** The case's state machine; see {@link CaseStatus}. */
    private CaseStatus next(CaseRow row, DecideCommand c) {
        CaseStatus s = row.status();
        return switch (c.action()) {
            case RELEASE -> {
                require(s == CaseStatus.OPEN || s == CaseStatus.ESCALATED, c, s);
                requireReason(c, DecisionReason.FALSE_POSITIVE);
                yield row.fourEyes() ? CaseStatus.PENDING_APPROVAL : CaseStatus.RELEASED;
            }
            case REJECT -> {
                require(s == CaseStatus.OPEN || s == CaseStatus.ESCALATED || s == CaseStatus.PENDING_APPROVAL, c, s);
                requireReason(c, DecisionReason.TRUE_MATCH);
                yield CaseStatus.REJECTED;
            }
            case ESCALATE -> {
                require(s == CaseStatus.OPEN, c, s);
                requireReason(c, DecisionReason.INSUFFICIENT_INFORMATION);
                yield CaseStatus.ESCALATED;
            }
            case APPROVE, DECLINE -> {
                require(s == CaseStatus.PENDING_APPROVAL, c, s);
                if (c.decidedBy().equals(releaser(row.id()))) {
                    throw new ComplianceException(ComplianceError.FOUR_EYES, c.decidedBy() + " released this payment; "
                            + "a second person must " + (c.action() == DecisionAction.APPROVE ? "approve" : "decline") + " it (four-eyes principle)");
                }
                yield c.action() == DecisionAction.APPROVE ? CaseStatus.RELEASED : CaseStatus.OPEN;
            }
            case WITHDRAW -> {
                require(s == CaseStatus.PENDING_APPROVAL, c, s);
                if (!c.decidedBy().equals(releaser(row.id()))) {
                    throw new ComplianceException(ComplianceError.ILLEGAL_DECISION, "Only " + releaser(row.id())
                            + ", who released it, can withdraw the release");
                }
                yield CaseStatus.OPEN;
            }
        };
    }

    private String releaser(UUID caseId) {
        return repo.decisions(caseId).stream().filter(d -> d.action() == DecisionAction.RELEASE)
                .reduce((a, b) -> b).map(DecisionView::decidedBy).orElse(null);
    }

    private static void require(boolean ok, DecideCommand c, CaseStatus s) {
        if (!ok) {
            throw new ComplianceException(ComplianceError.ILLEGAL_DECISION,
                    "Cannot " + c.action().name().toLowerCase() + " a case that is " + s + (s.isClosed() ? " (already decided)" : ""));
        }
    }

    private static void requireReason(DecideCommand c, DecisionReason expected) {
        if (c.reason() != expected) {
            throw new ComplianceException(ComplianceError.INVALID_REQUEST,
                    c.action() + " needs reason " + expected + (c.reason() == null ? "" : ", not " + c.reason()));
        }
    }

    private static void validate(DecideCommand c) {
        if (c.idempotencyKey() == null || c.idempotencyKey().isBlank()) {
            throw new ComplianceException(ComplianceError.INVALID_REQUEST, "Idempotency-Key is required");
        }
        if (c.caseId() == null || c.action() == null) {
            throw new ComplianceException(ComplianceError.INVALID_REQUEST, "case and action are required");
        }
        if (c.decidedBy() == null || !c.decidedBy().matches("[a-z0-9._-]{1,64}")) {
            throw new ComplianceException(ComplianceError.INVALID_REQUEST, "decidedBy must be a user id such as n.rahman");
        }
        int len = c.comment() == null ? 0 : c.comment().trim().length();
        if (len < 20 || len > 500) {
            throw new ComplianceException(ComplianceError.INVALID_REQUEST,
                    "A comment of 20–500 characters is required: it is the audit trail of the decision");
        }
    }

    private static String fingerprint(DecideCommand c) {
        String s = String.join("\u001F", c.caseId().toString(), c.action().name(), String.valueOf(c.reason()),
                c.comment().trim(), c.decidedBy());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ComplianceException notFound(UUID caseId) {
        return new ComplianceException(ComplianceError.NOT_FOUND, "No case " + caseId);
    }

    // ------------------------------------------------------------------ queries

    @Override
    public List<CaseView> cases(boolean open, String listCode, LocalDate closedOn) {
        LocalDate day = closedOn != null ? closedOn : today();
        Instant from = open ? null : day.atStartOfDay(bankZone).toInstant();
        Instant to = open ? null : day.plusDays(1).atStartOfDay(bankZone).toInstant();
        return repo.cases(open, listCode, from, to).stream().map(this::view).toList();
    }

    @Override
    public CaseView getCase(UUID caseId) {
        return view(repo.findCase(caseId).orElseThrow(() -> notFound(caseId)));
    }

    @Override
    public Optional<CaseView> caseForPayment(UUID paymentId) {
        return repo.findCaseByPayment(paymentId).map(this::view);
    }

    @Override
    public Optional<ScreeningView> screeningForPayment(UUID paymentId) {
        return repo.findScreeningByPayment(paymentId).map(s -> {
            Optional<CaseRow> c = repo.findCaseByPayment(paymentId);
            return new ScreeningView(s.id(), s.paymentId(), s.screenedAt(), s.asOf(), s.lists(), s.screenedItems(),
                    s.holdThreshold(), s.bestScore(), s.outcome(), c.map(CaseRow::id).orElse(null),
                    c.map(CaseRow::status).orElse(null));
        });
    }

    private CaseView view(CaseRow c) {
        ScreeningRow s = repo.findScreening(c.screeningId()).orElseThrow();
        return new CaseView(c.id(), c.paymentId(), s.uetr(), s.direction(), s.kind(), Money.of(s.amount(), s.currency()),
                s.debtorName(), s.creditorName(), s.route(), c.status(), c.bestScore(), c.fourEyes(), c.heldAt(),
                c.heldAt().plus(props.sla()), c.closedAt(), s.asOf(), s.lists(), repo.hits(s.id()), repo.decisions(c.id()));
    }

    @Override
    public ComplianceStats stats() {
        ComplianceRepository.OpenCounts open = repo.openCounts();
        LocalDate today = today();
        ComplianceRepository.ClosedCounts closed = repo.closedBetween(today.atStartOfDay(bankZone).toInstant(),
                today.plusDays(1).atStartOfDay(bankZone).toInstant());
        Map<String, Integer> byList = new LinkedHashMap<>();
        for (Object[] row : repo.openByList()) {
            byList.put((String) row[0], (Integer) row[1]);
        }
        return new ComplianceStats(open.pending(), open.awaitingApproval(), open.escalated(), open.oldestHeldAt(),
                closed.released(), closed.rejected(), closed.medianMinutes(), props.sla().toMinutes(), byList);
    }

    @Override
    public ComplianceSettings settings() {
        List<String> lists = props.lists().isEmpty()
                ? repo.lists(today()).stream().map(SanctionsListView::code).toList() : props.lists();
        return new ComplianceSettings(props.holdThreshold(), props.fourEyesThreshold(), props.sla().toMinutes(), lists);
    }

    @Override
    public List<SanctionsListView> lists(LocalDate asOf) {
        return repo.lists(asOf != null ? asOf : today());
    }

    @Override
    public List<SanctionsEntryView> entries(String listCode, String query, LocalDate asOf) {
        return repo.entries(listCode, query == null || query.isBlank() ? null : query.trim(), asOf);
    }

    @Override
    public String decisionsCsv(LocalDate from, LocalDate to) {
        StringBuilder sb = new StringBuilder("decided_at,case_id,payment_id,uetr,best_score,action,reason,decided_by,status_before,status_after,comment\n");
        for (ComplianceRepository.AuditRow r : repo.decisionsBetween(from.atStartOfDay(bankZone).toInstant(),
                to.plusDays(1).atStartOfDay(bankZone).toInstant())) {
            DecisionView d = r.decision();
            sb.append(d.decidedAt()).append(',').append(r.caseId()).append(',').append(r.paymentId()).append(',')
                    .append(r.uetr()).append(',').append(r.bestScore()).append(',').append(d.action()).append(',')
                    .append(d.reason() == null ? "" : d.reason()).append(',').append(d.decidedBy()).append(',')
                    .append(d.statusBefore()).append(',').append(d.statusAfter()).append(',')
                    .append('"').append(d.comment().replace("\"", "\"\"")).append('"').append('\n');
        }
        return sb.toString();
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(bankZone));
    }
}
