package com.minipay.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minipay.common.money.Money;
import com.minipay.compliance.api.CaseStatus;
import com.minipay.compliance.api.CaseVerdict;
import com.minipay.compliance.api.CaseView;
import com.minipay.compliance.api.ComplianceApi;
import com.minipay.compliance.api.ComplianceDecisionMade;
import com.minipay.compliance.api.ComplianceError;
import com.minipay.compliance.api.ComplianceException;
import com.minipay.compliance.api.DecideCommand;
import com.minipay.compliance.api.DecisionAction;
import com.minipay.compliance.api.DecisionReason;
import com.minipay.compliance.api.DecisionResult;
import com.minipay.compliance.api.PartyRole;
import com.minipay.compliance.api.ScreenedParty;
import com.minipay.compliance.api.ScreeningOutcome;
import com.minipay.compliance.api.ScreeningRequest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * Screening and decisions against real PostgreSQL. Screenings and decisions are append-only, so tests
 * never clean up: each screens its own payment (a fresh payment id) and asserts only on it.
 */
@SpringBootTest(classes = ComplianceTestApplication.class)
@RecordApplicationEvents
class ComplianceIT {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 24);
    static final String COMMENT_FP = "Different entity: GB company reg 09811234, Leeds address; list entry is a UAE free-zone";
    static final String COMMENT_TM = "Confirmed with the debtor: the beneficiary is the listed free-zone company";

    @Autowired
    ComplianceApi compliance;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationEvents events;

    static ScreeningRequest payment(UUID id, String creditor, String creditorIban, LocalDate asOf) {
        return new ScreeningRequest(id, UUID.randomUUID(), "OUTGOING", "CUSTOMER", Money.of("7840.00", "GBP"),
                "Muar Furniture Works Sdn Bhd", creditor, "MNPAMYKLXXX → MNPCGB2LXXX", List.of(
                new ScreenedParty(PartyRole.DEBTOR, "Dbtr/Nm", "Muar Furniture Works Sdn Bhd", "1200-0044-7781", "MNPAMYKLXXX"),
                new ScreenedParty(PartyRole.CREDITOR, "Cdtr/Nm", creditor, creditorIban, "MNPCGB2LXXX"),
                new ScreenedParty(PartyRole.DEBTOR_AGENT, "DbtrAgt/FinInstnId/BICFI", null, null, "MNPAMYKLXXX"),
                new ScreenedParty(PartyRole.CREDITOR_AGENT, "CdtrAgt/FinInstnId/BICFI", null, null, "MNPCGB2LXXX")), asOf);
    }

    /** Oakridge scores ≥ 90: its release needs two people. */
    ScreeningOutcome holdFourEyes() {
        return compliance.screen(payment(UUID.randomUUID(), "Oakridge Interiors Ltd", "GB86MNPC40051288334410", TODAY));
    }

    /** Halvorsen scores < 90: one person can release it. */
    ScreeningOutcome holdSingle() {
        return compliance.screen(payment(UUID.randomUUID(), "Halvorsen Shipping AS", "DE51500100600123456789", TODAY));
    }

    DecisionResult decide(UUID caseId, DecisionAction action, DecisionReason reason, String by) {
        return compliance.decide(new DecideCommand(UUID.randomUUID().toString(), caseId, action, reason,
                reason == DecisionReason.TRUE_MATCH ? COMMENT_TM : COMMENT_FP, by));
    }

    static ComplianceError error(Throwable t) {
        return ((ComplianceException) t).error();
    }

    // ------------------------------------------------------------------ screening

    @Test
    void an_ordinary_payment_is_clear_and_says_against_what() {
        ScreeningOutcome o = compliance.screen(payment(UUID.randomUUID(), "Lee Mei Ling", "GB86MNPC40051288334410", TODAY));
        assertThat(o.hit()).isFalse();
        assertThat(o.caseId()).isNull();
        assertThat(o.detail()).contains("Screened 4 names and BICs against OFAC 2026-11-20, EU 2026-11-20 (as of 2026-11-24): no match ≥ 75");
    }

    @Test
    void a_hit_opens_a_case_that_explains_the_match() {
        ScreeningOutcome o = holdFourEyes();
        assertThat(o.hit()).isTrue();
        assertThat(o.fourEyes()).isTrue();
        assertThat(o.detail()).startsWith("Held for review: Cdtr/Nm “Oakridge Interiors Ltd” ≈ OAKRIDGE INTERIORS TRADING FZE (EU.SIM.4417");

        CaseView c = compliance.getCase(o.caseId());
        assertThat(c.status()).isEqualTo(CaseStatus.OPEN);
        assertThat(c.slaDeadline()).isEqualTo(c.heldAt().plusSeconds(7200));
        assertThat(c.hits()).hasSize(1);
        assertThat(c.hits().get(0).listName()).isEqualTo("EU consolidated (sim)");
        assertThat(c.hits().get(0).factors()).isNotEmpty();
        assertThat(c.hits().get(0).comparison()).extracting(r -> r.attribute()).contains("Country", "Registration", "Listing");
        assertThat(compliance.verdict(c.paymentId()).verdict()).isEqualTo(CaseVerdict.Verdict.PENDING);
    }

    @Test
    void screening_a_payment_again_returns_the_first_screening() {
        UUID paymentId = UUID.randomUUID();
        ScreeningOutcome first = compliance.screen(payment(paymentId, "Oakridge Interiors Ltd", null, TODAY));
        // A re-run of the step, even with different content, cannot clear a held payment.
        ScreeningOutcome again = compliance.screen(payment(paymentId, "Lee Mei Ling", null, TODAY));
        assertThat(again.screeningId()).isEqualTo(first.screeningId());
        assertThat(again.hit()).isTrue();
        assertThat(again.caseId()).isEqualTo(first.caseId());
    }

    @Test
    void workers_racing_to_screen_one_payment_create_one_screening_and_one_case() throws Exception {
        UUID paymentId = UUID.randomUUID();
        List<ScreeningOutcome> outcomes = race(10, () -> compliance.screen(payment(paymentId, "Oakridge Interiors Ltd", null, TODAY)));
        assertThat(outcomes).extracting(ScreeningOutcome::screeningId).containsOnly(outcomes.get(0).screeningId());
        assertThat(jdbc.sql("SELECT count(*) FROM compliance.screening_case WHERE payment_id = :p").param("p", paymentId)
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void lists_are_screened_as_of_a_date_and_a_delisted_entry_no_longer_matches() {
        // MERIDIAN SALT TRADING LLC was delisted on 1 Oct 2025.
        assertThat(compliance.screen(payment(UUID.randomUUID(), "Meridian Salt Trading", null, LocalDate.of(2025, 9, 30))).hit()).isTrue();
        assertThat(compliance.screen(payment(UUID.randomUUID(), "Meridian Salt Trading", null, TODAY)).hit()).isFalse();
        // OAKRIDGE was listed on 14 Mar 2026: a payment screened before that day would have passed.
        assertThat(compliance.screen(payment(UUID.randomUUID(), "Oakridge Interiors Ltd", null, LocalDate.of(2026, 3, 13))).hit()).isFalse();

        assertThat(compliance.entries("OFAC", "Meridian", null)).singleElement()
                .satisfies(e -> assertThat(e.listedTo()).isEqualTo(LocalDate.of(2025, 9, 30)));
        assertThat(compliance.entries("OFAC", "Meridian", TODAY)).isEmpty();
    }

    @Test
    void a_listed_bank_in_the_route_holds_the_payment() {
        ScreeningRequest r = payment(UUID.randomUUID(), "Lee Mei Ling", null, TODAY);
        List<ScreenedParty> parties = new ArrayList<>(r.parties());
        parties.add(new ScreenedParty(PartyRole.INSTRUCTED_AGENT, "InstdAgt/FinInstnId/BICFI", null, null, "MNPZSYDAXXX"));
        ScreeningOutcome o = compliance.screen(new ScreeningRequest(r.paymentId(), r.uetr(), r.direction(), r.kind(), r.amount(),
                r.debtorName(), r.creditorName(), r.route(), parties, TODAY));
        assertThat(o.hit()).isTrue();
        assertThat(o.bestScore()).isEqualTo(100);
        assertThat(compliance.getCase(o.caseId()).hits().get(0).matchedOn()).isEqualTo("BIC");
    }

    // ------------------------------------------------------------------ decisions

    @Test
    void a_release_below_the_four_eyes_threshold_closes_the_case_and_tells_the_hub() {
        ScreeningOutcome o = holdSingle();
        assertThat(o.fourEyes()).isFalse();
        CaseView c = decide(o.caseId(), DecisionAction.RELEASE, DecisionReason.FALSE_POSITIVE, "n.rahman").caseView();
        assertThat(c.status()).isEqualTo(CaseStatus.RELEASED);
        assertThat(c.closedAt()).isNotNull();
        assertThat(events.stream(ComplianceDecisionMade.class))
                .contains(new ComplianceDecisionMade(c.id(), c.paymentId(), CaseStatus.RELEASED));
        CaseVerdict v = compliance.verdict(c.paymentId());
        assertThat(v.verdict()).isEqualTo(CaseVerdict.Verdict.RELEASED);
        assertThat(v.summary()).startsWith("Compliance: released by n.rahman (false positive): “Different entity");
    }

    @Test
    void a_high_score_release_needs_a_second_person() {
        ScreeningOutcome o = holdFourEyes();
        CaseView c = decide(o.caseId(), DecisionAction.RELEASE, DecisionReason.FALSE_POSITIVE, "n.rahman").caseView();
        assertThat(c.status()).isEqualTo(CaseStatus.PENDING_APPROVAL);
        assertThat(compliance.verdict(c.paymentId()).verdict()).isEqualTo(CaseVerdict.Verdict.PENDING);
        assertThat(events.stream(ComplianceDecisionMade.class)).noneMatch(e -> e.caseId().equals(c.id()));

        assertThatThrownBy(() -> decide(o.caseId(), DecisionAction.APPROVE, null, "n.rahman"))
                .satisfies(t -> assertThat(error(t)).isEqualTo(ComplianceError.FOUR_EYES));

        CaseView approved = decide(o.caseId(), DecisionAction.APPROVE, null, "j.tan").caseView();
        assertThat(approved.status()).isEqualTo(CaseStatus.RELEASED);
        assertThat(approved.decisions()).extracting(d -> d.action() + " " + d.decidedBy() + " " + d.statusAfter())
                .containsExactly("RELEASE n.rahman PENDING_APPROVAL", "APPROVE j.tan RELEASED");
        CaseVerdict v = compliance.verdict(c.paymentId());
        assertThat(v.summary()).startsWith("Compliance: released by n.rahman, approved by j.tan (false positive)");
        assertThat(v.decidedBy()).isEqualTo("n.rahman+j.tan");
    }

    @Test
    void a_second_person_can_decline_and_the_releaser_can_withdraw() {
        UUID caseId = holdFourEyes().caseId();
        decide(caseId, DecisionAction.RELEASE, DecisionReason.FALSE_POSITIVE, "n.rahman");
        assertThat(decide(caseId, DecisionAction.DECLINE, null, "j.tan").caseView().status()).isEqualTo(CaseStatus.OPEN);

        decide(caseId, DecisionAction.RELEASE, DecisionReason.FALSE_POSITIVE, "n.rahman");
        assertThatThrownBy(() -> decide(caseId, DecisionAction.WITHDRAW, null, "j.tan"))
                .satisfies(t -> assertThat(error(t)).isEqualTo(ComplianceError.ILLEGAL_DECISION));
        assertThat(decide(caseId, DecisionAction.WITHDRAW, null, "n.rahman").caseView().status()).isEqualTo(CaseStatus.OPEN);
    }

    @Test
    void a_rejection_is_final() {
        UUID caseId = holdFourEyes().caseId();
        decide(caseId, DecisionAction.ESCALATE, DecisionReason.INSUFFICIENT_INFORMATION, "n.rahman");
        CaseView c = decide(caseId, DecisionAction.REJECT, DecisionReason.TRUE_MATCH, "a.mlro").caseView();
        assertThat(c.status()).isEqualTo(CaseStatus.REJECTED);
        assertThat(compliance.verdict(c.paymentId()).verdict()).isEqualTo(CaseVerdict.Verdict.REJECTED);
        assertThat(events.stream(ComplianceDecisionMade.class)).contains(new ComplianceDecisionMade(caseId, c.paymentId(), CaseStatus.REJECTED));

        assertThatThrownBy(() -> decide(caseId, DecisionAction.RELEASE, DecisionReason.FALSE_POSITIVE, "j.tan"))
                .satisfies(t -> assertThat(error(t)).isEqualTo(ComplianceError.ILLEGAL_DECISION));
        // Not even SQL can reopen it.
        assertThatThrownBy(() -> jdbc.sql("UPDATE compliance.screening_case SET status = 'OPEN', closed_at = NULL WHERE id = :id")
                .param("id", caseId).update()).hasMessageContaining("is closed");
    }

    @Test
    void every_decision_needs_its_reason_and_a_real_comment() {
        UUID caseId = holdSingle().caseId();
        assertThatThrownBy(() -> decide(caseId, DecisionAction.RELEASE, DecisionReason.TRUE_MATCH, "n.rahman"))
                .satisfies(t -> assertThat(error(t)).isEqualTo(ComplianceError.INVALID_REQUEST));
        assertThatThrownBy(() -> compliance.decide(new DecideCommand("k-" + UUID.randomUUID(), caseId, DecisionAction.RELEASE,
                DecisionReason.FALSE_POSITIVE, "looks fine", "n.rahman")))
                .satisfies(t -> assertThat(error(t)).isEqualTo(ComplianceError.INVALID_REQUEST))
                .hasMessageContaining("20–500");
    }

    @Test
    void a_decision_replays_by_idempotency_key_and_refuses_a_different_body() {
        UUID caseId = holdSingle().caseId();
        String key = "dec-" + UUID.randomUUID();
        DecideCommand release = new DecideCommand(key, caseId, DecisionAction.RELEASE, DecisionReason.FALSE_POSITIVE, COMMENT_FP, "n.rahman");
        assertThat(compliance.decide(release).replayed()).isFalse();
        DecisionResult replay = compliance.decide(release);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.caseView().decisions()).hasSize(1);
        assertThatThrownBy(() -> compliance.decide(new DecideCommand(key, caseId, DecisionAction.REJECT, DecisionReason.TRUE_MATCH,
                COMMENT_TM, "n.rahman"))).satisfies(t -> assertThat(error(t)).isEqualTo(ComplianceError.IDEMPOTENCY_CONFLICT));
    }

    /**
     * Two officers release the same case at the same moment. One release is recorded; the other is told
     * the case is already waiting for approval. Proved by mutation: without the case row lock
     * ({@code FOR UPDATE} in {@code lockCase}) both releases are recorded, both "from OPEN".
     */
    @Test
    void officers_deciding_the_same_case_at_once_take_turns() throws Exception {
        for (int round = 0; round < 5; round++) {
            UUID caseId = holdFourEyes().caseId();
            List<Object> results = race(2, () -> {
                try {
                    return decide(caseId, DecisionAction.RELEASE, DecisionReason.FALSE_POSITIVE, "u" + Thread.currentThread().threadId());
                } catch (ComplianceException e) {
                    return e.error();
                }
            });
            assertThat(results).filteredOn(r -> r instanceof DecisionResult).hasSize(1);
            assertThat(results).filteredOn(r -> r == ComplianceError.ILLEGAL_DECISION).hasSize(1);
            assertThat(compliance.getCase(caseId).decisions()).hasSize(1);
        }
    }

    @Test
    void the_database_enforces_four_eyes_and_keeps_every_decision() {
        UUID caseId = holdFourEyes().caseId();
        decide(caseId, DecisionAction.RELEASE, DecisionReason.FALSE_POSITIVE, "n.rahman");
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO compliance.decision (case_id, action, comment, decided_by, decided_at, status_before, status_after,
                            idempotency_key, request_hash)
                        VALUES (:c, 'APPROVE', 'approving my own release, bypassing the API', 'n.rahman', now(), 'PENDING_APPROVAL',
                            'RELEASED', :k, repeat('0', 64))
                        """).param("c", caseId).param("k", "sql-" + UUID.randomUUID()).update())
                .hasMessageContaining("four-eyes");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM compliance.decision WHERE case_id = :c").param("c", caseId).update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("UPDATE compliance.sanctions_entry SET name = 'X' WHERE entry_id = 'EU.SIM.4417'").update())
                .hasMessageContaining("only be end-dated");
    }

    @Test
    void the_queue_counts_and_the_audit_file() {
        int before = compliance.stats().pending();
        UUID open = holdSingle().caseId();
        UUID released = holdSingle().caseId();
        decide(released, DecisionAction.RELEASE, DecisionReason.FALSE_POSITIVE, "n.rahman");
        assertThat(compliance.stats().pending()).isEqualTo(before + 1);
        assertThat(compliance.stats().releasedToday()).isPositive();
        assertThat(compliance.stats().pendingByList()).containsKey("EU");
        assertThat(compliance.cases(true, "EU", null)).extracting(CaseView::id).contains(open).doesNotContain(released);
        assertThat(compliance.cases(true, "OFAC", null)).extracting(CaseView::id).doesNotContain(open);
        assertThat(compliance.cases(false, null, TODAY)).extracting(CaseView::id).contains(released);
        assertThat(compliance.decisionsCsv(TODAY, TODAY)).startsWith("decided_at,case_id").contains(released + "," );
    }

    static <T> List<T> race(int n, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
