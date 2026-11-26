package com.minipay.compliance.internal.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.compliance.api.CaseStatus;
import com.minipay.compliance.api.ComparisonRow;
import com.minipay.compliance.api.DecisionAction;
import com.minipay.compliance.api.DecisionReason;
import com.minipay.compliance.api.DecisionView;
import com.minipay.compliance.api.Factor;
import com.minipay.compliance.api.HitView;
import com.minipay.compliance.api.NameToken;
import com.minipay.compliance.api.PartyRole;
import com.minipay.compliance.api.SanctionsEntryView;
import com.minipay.compliance.api.SanctionsListView;
import com.minipay.compliance.internal.engine.ListEntry;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** JDBC access to schema {@code compliance}. */
@Repository
public class ComplianceRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ComplianceRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ------------------------------------------------------------------ lists

    private static final String ENTRY_COLUMNS = """
            e.id, e.entry_id, e.list_code, l.name AS list_name, e.entry_type, e.name, e.aliases, e.country, e.address,
            e.registration, e.date_of_birth, e.bic, e.program, lower(e.valid) AS valid_from, upper(e.valid) AS valid_to
            """;

    /** Entries in force on {@code asOf}, of the given lists (all lists if empty). */
    public List<ListEntry> activeEntries(LocalDate asOf, List<String> lists) {
        return jdbc.sql("SELECT " + ENTRY_COLUMNS + """
                        FROM compliance.sanctions_entry e JOIN compliance.sanctions_list l ON l.code = e.list_code
                        WHERE e.valid @> CAST(:d AS date) AND (cardinality(CAST(:lists AS text[])) = 0 OR e.list_code = ANY(CAST(:lists AS text[])))
                        ORDER BY e.list_code, e.entry_id
                        """)
                .param("d", asOf).param("lists", lists.toArray(String[]::new))
                .query((rs, n) -> entry(rs)).list();
    }

    /** "OFAC 2026-11-20, EU 2026-11-20": the latest load of each list on or before {@code asOf}. */
    public String snapshots(LocalDate asOf, List<String> lists) {
        List<String> parts = jdbc.sql("""
                        SELECT list_code || ' ' || max(snapshot_date) FROM compliance.list_snapshot
                        WHERE snapshot_date <= :d AND (cardinality(CAST(:lists AS text[])) = 0 OR list_code = ANY(CAST(:lists AS text[])))
                        GROUP BY list_code ORDER BY list_code DESC
                        """)
                .param("d", asOf).param("lists", lists.toArray(String[]::new))
                .query(String.class).list();
        return parts.isEmpty() ? "no list loaded" : String.join(", ", parts);
    }

    public List<SanctionsListView> lists(LocalDate asOf) {
        return jdbc.sql("""
                        SELECT l.code, l.name, l.authority,
                               (SELECT max(snapshot_date) FROM compliance.list_snapshot s WHERE s.list_code = l.code) AS snapshot,
                               (SELECT count(*) FROM compliance.sanctions_entry e WHERE e.list_code = l.code AND e.valid @> CAST(:d AS date)) AS active
                        FROM compliance.sanctions_list l ORDER BY l.code DESC
                        """)
                .param("d", asOf)
                .query((rs, n) -> new SanctionsListView(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, LocalDate.class), rs.getInt(5)))
                .list();
    }

    /** @param asOf null = every version of every entry, including delisted ones */
    public List<SanctionsEntryView> entries(String listCode, String query, LocalDate asOf) {
        return jdbc.sql("SELECT " + ENTRY_COLUMNS + """
                        FROM compliance.sanctions_entry e JOIN compliance.sanctions_list l ON l.code = e.list_code
                        WHERE (CAST(:list AS text) IS NULL OR e.list_code = :list)
                          AND (CAST(:d AS date) IS NULL OR e.valid @> CAST(:d AS date))
                          AND (CAST(:q AS text) IS NULL OR e.name ILIKE '%' || :q || '%' OR e.entry_id ILIKE '%' || :q || '%'
                               OR array_to_string(e.aliases, ' ') ILIKE '%' || :q || '%')
                        ORDER BY e.list_code DESC, e.entry_id, lower(e.valid)
                        """)
                .param("list", listCode).param("d", asOf).param("q", query)
                .query((rs, n) -> {
                    ListEntry e = entry(rs);
                    return new SanctionsEntryView(e.entryId(), e.listCode(), e.type(), e.name(), e.aliases(), e.country(),
                            e.address(), e.registration(), e.dateOfBirth(), e.bic(), e.program(), e.validFrom(),
                            e.validTo() == null ? null : e.validTo().minusDays(1));
                })
                .list();
    }

    private static ListEntry entry(ResultSet rs) throws SQLException {
        return new ListEntry(rs.getLong("id"), rs.getString("entry_id"), rs.getString("list_code"), rs.getString("list_name"),
                rs.getString("entry_type"), rs.getString("name"), strings(rs.getArray("aliases")), rs.getString("country"),
                rs.getString("address"), rs.getString("registration"), rs.getObject("date_of_birth", LocalDate.class),
                rs.getString("bic"), rs.getString("program"), rs.getObject("valid_from", LocalDate.class),
                rs.getObject("valid_to", LocalDate.class));
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    // ------------------------------------------------------------------ screenings

    public record ScreeningRow(UUID id, UUID paymentId, UUID uetr, String direction, String kind, BigDecimal amount,
                               String currency, String debtorName, String creditorName, String route, LocalDate asOf,
                               String lists, int screenedItems, int holdThreshold, int bestScore, String outcome,
                               Instant screenedAt) {
    }

    /** @return false if the payment was already screened (a concurrent or repeated run): nothing was written */
    public boolean insertScreening(ScreeningRow s, Object request) {
        return jdbc.sql("""
                        INSERT INTO compliance.screening (id, payment_id, uetr, direction, kind, amount, currency, debtor_name,
                            creditor_name, route, request, as_of, lists, hold_threshold, best_score, outcome, screened_at)
                        VALUES (:id, :payment, :uetr, :direction, :kind, :amount, :currency, :debtor, :creditor, :route,
                            CAST(:request AS jsonb), :asOf, :lists, :hold, :best, :outcome, :at)
                        ON CONFLICT (payment_id) DO NOTHING
                        """)
                .param("id", s.id()).param("payment", s.paymentId()).param("uetr", s.uetr()).param("direction", s.direction())
                .param("kind", s.kind()).param("amount", s.amount()).param("currency", s.currency())
                .param("debtor", s.debtorName()).param("creditor", s.creditorName()).param("route", s.route())
                .param("request", write(request)).param("asOf", s.asOf()).param("lists", s.lists())
                .param("hold", s.holdThreshold()).param("best", s.bestScore()).param("outcome", s.outcome())
                .param("at", utc(s.screenedAt()))
                .update() == 1;
    }

    private static final String SCREENING_COLUMNS = """
            s.id, s.payment_id, s.uetr, s.direction, s.kind, s.amount, s.currency, s.debtor_name, s.creditor_name, s.route,
            s.as_of, s.lists, jsonb_array_length(s.request) AS items, s.hold_threshold, s.best_score, s.outcome, s.screened_at
            """;

    public Optional<ScreeningRow> findScreeningByPayment(UUID paymentId) {
        return jdbc.sql("SELECT " + SCREENING_COLUMNS + " FROM compliance.screening s WHERE s.payment_id = :p")
                .param("p", paymentId).query((rs, n) -> screening(rs)).optional();
    }

    public Optional<ScreeningRow> findScreening(UUID id) {
        return jdbc.sql("SELECT " + SCREENING_COLUMNS + " FROM compliance.screening s WHERE s.id = :id")
                .param("id", id).query((rs, n) -> screening(rs)).optional();
    }

    private static ScreeningRow screening(ResultSet rs) throws SQLException {
        return new ScreeningRow(rs.getObject("id", UUID.class), rs.getObject("payment_id", UUID.class),
                rs.getObject("uetr", UUID.class), rs.getString("direction"), rs.getString("kind"), rs.getBigDecimal("amount"),
                rs.getString("currency"), rs.getString("debtor_name"), rs.getString("creditor_name"), rs.getString("route"),
                rs.getObject("as_of", LocalDate.class), rs.getString("lists"), rs.getInt("items"), rs.getInt("hold_threshold"),
                rs.getInt("best_score"), rs.getString("outcome"), instant(rs, "screened_at"));
    }

    /** What a hit says beyond its columns, stored as JSON with it. */
    public record HitDetail(String partyAccount, String partyBic, List<NameToken> partyTokens, List<NameToken> listedTokens,
                            List<Factor> factors, List<ComparisonRow> comparison, String hint) {
    }

    public void insertHit(UUID screeningId, PartyRole role, String field, String partyName, long entryPk, String matchedOn,
                          String matchedName, int score, HitDetail detail) {
        jdbc.sql("""
                        INSERT INTO compliance.screening_hit (screening_id, party_role, field, party_name, entry_pk, matched_on,
                            matched_name, score, detail)
                        VALUES (:s, :role, :field, :name, :entry, :on, :matched, :score, CAST(:detail AS jsonb))
                        """)
                .param("s", screeningId).param("role", role.name()).param("field", field).param("name", partyName)
                .param("entry", entryPk).param("on", matchedOn).param("matched", matchedName).param("score", score)
                .param("detail", write(detail))
                .update();
    }

    public List<HitView> hits(UUID screeningId) {
        return jdbc.sql("""
                        SELECT h.id, h.party_role, h.field, h.party_name, h.matched_on, h.matched_name, h.score, h.detail,
                               e.entry_id, e.list_code, l.name AS list_name, e.entry_type, e.name AS listed_name, e.program,
                               lower(e.valid) AS valid_from, upper(e.valid) AS valid_to
                        FROM compliance.screening_hit h
                        JOIN compliance.sanctions_entry e ON e.id = h.entry_pk
                        JOIN compliance.sanctions_list l ON l.code = e.list_code
                        WHERE h.screening_id = :s ORDER BY h.score DESC, h.id
                        """)
                .param("s", screeningId)
                .query((rs, n) -> {
                    HitDetail d = read(rs.getString("detail"));
                    LocalDate to = rs.getObject("valid_to", LocalDate.class);
                    return new HitView(rs.getLong("id"), PartyRole.valueOf(rs.getString("party_role")), rs.getString("field"),
                            rs.getString("party_name"), rs.getString("entry_id"), rs.getString("list_code"),
                            rs.getString("list_name"), rs.getString("entry_type"), rs.getString("listed_name"),
                            rs.getString("matched_on"), rs.getString("matched_name"), rs.getInt("score"), d.partyTokens(),
                            d.listedTokens(), d.factors(), d.comparison(), d.hint(), rs.getObject("valid_from", LocalDate.class),
                            to == null ? null : to.minusDays(1), rs.getString("program"));
                })
                .list();
    }

    // ------------------------------------------------------------------ cases

    public record CaseRow(UUID id, UUID screeningId, UUID paymentId, int bestScore, boolean fourEyes, CaseStatus status,
                          Instant heldAt, Instant closedAt) {
    }

    public void insertCase(CaseRow c) {
        jdbc.sql("""
                        INSERT INTO compliance.screening_case (id, screening_id, payment_id, best_score, four_eyes, status, held_at)
                        VALUES (:id, :s, :p, :best, :four, :status, :held)
                        """)
                .param("id", c.id()).param("s", c.screeningId()).param("p", c.paymentId()).param("best", c.bestScore())
                .param("four", c.fourEyes()).param("status", c.status().name()).param("held", utc(c.heldAt()))
                .update();
    }

    private static final String CASE_COLUMNS = "c.id, c.screening_id, c.payment_id, c.best_score, c.four_eyes, c.status, c.held_at, c.closed_at";

    public Optional<CaseRow> findCase(UUID id) {
        return jdbc.sql("SELECT " + CASE_COLUMNS + " FROM compliance.screening_case c WHERE c.id = :id")
                .param("id", id).query((rs, n) -> caseRow(rs)).optional();
    }

    public Optional<CaseRow> findCaseByPayment(UUID paymentId) {
        return jdbc.sql("SELECT " + CASE_COLUMNS + " FROM compliance.screening_case c WHERE c.payment_id = :p")
                .param("p", paymentId).query((rs, n) -> caseRow(rs)).optional();
    }

    /**
     * Locks the case for a decision. Two officers deciding the same case at the same moment take turns:
     * the second one sees the first one's decision and is told so, instead of both succeeding.
     */
    public Optional<CaseRow> lockCase(UUID id) {
        return jdbc.sql("SELECT " + CASE_COLUMNS + " FROM compliance.screening_case c WHERE c.id = :id FOR UPDATE")
                .param("id", id).query((rs, n) -> caseRow(rs)).optional();
    }

    public void updateCaseStatus(UUID id, CaseStatus status, Instant closedAt) {
        jdbc.sql("UPDATE compliance.screening_case SET status = :status, closed_at = :closed WHERE id = :id")
                .param("id", id).param("status", status.name()).param("closed", closedAt == null ? null : utc(closedAt))
                .update();
    }

    /** @param open true: waiting for a decision, oldest first; false: closed in [from, to), latest first */
    public List<CaseRow> cases(boolean open, String listCode, Instant from, Instant to) {
        return jdbc.sql("SELECT " + CASE_COLUMNS + " FROM compliance.screening_case c WHERE "
                        + (open ? "c.closed_at IS NULL" : "c.closed_at >= :from AND c.closed_at < :to") + """
                         AND (CAST(:list AS text) IS NULL OR EXISTS (
                            SELECT 1 FROM compliance.screening_hit h JOIN compliance.sanctions_entry e ON e.id = h.entry_pk
                            WHERE h.screening_id = c.screening_id AND e.list_code = :list))
                        """ + (open ? " ORDER BY c.held_at, c.id" : " ORDER BY c.closed_at DESC, c.id") + " LIMIT 200")
                .param("list", listCode).param("from", from == null ? null : utc(from)).param("to", to == null ? null : utc(to))
                .query((rs, n) -> caseRow(rs)).list();
    }

    private static CaseRow caseRow(ResultSet rs) throws SQLException {
        return new CaseRow(rs.getObject("id", UUID.class), rs.getObject("screening_id", UUID.class),
                rs.getObject("payment_id", UUID.class), rs.getInt("best_score"), rs.getBoolean("four_eyes"),
                CaseStatus.valueOf(rs.getString("status")), instant(rs, "held_at"), instant(rs, "closed_at"));
    }

    // ------------------------------------------------------------------ decisions

    /** Concurrent requests with the same key take turns (transaction-scoped advisory lock). */
    public void lockIdempotencyKey(String key) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:k, 0))").param("k", "compliance:" + key).query().singleRow();
    }

    public record StoredKey(UUID caseId, String requestHash) {
    }

    public Optional<StoredKey> findDecisionKey(String key) {
        return jdbc.sql("SELECT case_id, request_hash FROM compliance.decision WHERE idempotency_key = :k")
                .param("k", key).query((rs, n) -> new StoredKey(rs.getObject(1, UUID.class), rs.getString(2))).optional();
    }

    public void insertDecision(UUID caseId, DecisionAction action, DecisionReason reason, String comment, String decidedBy,
                               Instant at, CaseStatus before, CaseStatus after, String key, String hash) {
        jdbc.sql("""
                        INSERT INTO compliance.decision (case_id, action, reason, comment, decided_by, decided_at, status_before,
                            status_after, idempotency_key, request_hash)
                        VALUES (:c, :action, :reason, :comment, :by, :at, :before, :after, :key, :hash)
                        """)
                .param("c", caseId).param("action", action.name()).param("reason", reason == null ? null : reason.name())
                .param("comment", comment).param("by", decidedBy).param("at", utc(at)).param("before", before.name())
                .param("after", after.name()).param("key", key).param("hash", hash)
                .update();
    }

    public List<DecisionView> decisions(UUID caseId) {
        return jdbc.sql("""
                        SELECT id, action, reason, comment, decided_by, decided_at, status_before, status_after
                        FROM compliance.decision WHERE case_id = :c ORDER BY id
                        """)
                .param("c", caseId).query((rs, n) -> decision(rs)).list();
    }

    private static DecisionView decision(ResultSet rs) throws SQLException {
        String reason = rs.getString("reason");
        return new DecisionView(rs.getLong("id"), DecisionAction.valueOf(rs.getString("action")),
                reason == null ? null : DecisionReason.valueOf(reason), rs.getString("comment"), rs.getString("decided_by"),
                instant(rs, "decided_at"), CaseStatus.valueOf(rs.getString("status_before")),
                CaseStatus.valueOf(rs.getString("status_after")));
    }

    public record AuditRow(DecisionView decision, UUID caseId, UUID paymentId, UUID uetr, int bestScore) {
    }

    public List<AuditRow> decisionsBetween(Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT d.id, d.action, d.reason, d.comment, d.decided_by, d.decided_at, d.status_before, d.status_after,
                               c.id AS case_id, c.payment_id, s.uetr, c.best_score
                        FROM compliance.decision d
                        JOIN compliance.screening_case c ON c.id = d.case_id
                        JOIN compliance.screening s ON s.id = c.screening_id
                        WHERE d.decided_at >= :from AND d.decided_at < :to ORDER BY d.decided_at, d.id
                        """)
                .param("from", utc(from)).param("to", utc(to))
                .query((rs, n) -> new AuditRow(decision(rs), rs.getObject("case_id", UUID.class),
                        rs.getObject("payment_id", UUID.class), rs.getObject("uetr", UUID.class), rs.getInt("best_score")))
                .list();
    }

    // ------------------------------------------------------------------ statistics

    public record OpenCounts(int pending, int awaitingApproval, int escalated, Instant oldestHeldAt) {
    }

    public OpenCounts openCounts() {
        return jdbc.sql("""
                        SELECT count(*), count(*) FILTER (WHERE status = 'PENDING_APPROVAL'),
                               count(*) FILTER (WHERE status = 'ESCALATED'), min(held_at)
                        FROM compliance.screening_case WHERE closed_at IS NULL
                        """)
                .query((rs, n) -> new OpenCounts(rs.getInt(1), rs.getInt(2), rs.getInt(3), instant(rs, 4))).single();
    }

    public record ClosedCounts(int released, int rejected, Long medianMinutes) {
    }

    public ClosedCounts closedBetween(Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE status = 'RELEASED'), count(*) FILTER (WHERE status = 'REJECTED'),
                               round(percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM closed_at - held_at)) / 60)
                        FROM compliance.screening_case WHERE closed_at >= :from AND closed_at < :to
                        """)
                .param("from", utc(from)).param("to", utc(to))
                .query((rs, n) -> new ClosedCounts(rs.getInt(1), rs.getInt(2), rs.getObject(3) == null ? null : rs.getLong(3)))
                .single();
    }

    public List<Object[]> openByList() {
        return jdbc.sql("""
                        SELECT e.list_code, count(DISTINCT c.id)
                        FROM compliance.screening_case c
                        JOIN compliance.screening_hit h ON h.screening_id = c.screening_id
                        JOIN compliance.sanctions_entry e ON e.id = h.entry_pk
                        WHERE c.closed_at IS NULL GROUP BY e.list_code
                        """)
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getInt(2)}).list();
    }

    // ------------------------------------------------------------------ helpers

    private static OffsetDateTime utc(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private HitDetail read(String s) {
        try {
            return json.readValue(s, HitDetail.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
