package com.minipay.recon.internal.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The recon schema, in plain SQL. */
@Repository
public class ReconRepository {

    private final JdbcClient jdbc;

    public ReconRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ statements

    public record StatementRow(UUID id, UUID messageId, String account, String servicerBic, String servicerAccount,
                               String statementRef, String msgId, LocalDate date, String currency, BigDecimal opening,
                               BigDecimal closing, Instant receivedAt) {
    }

    /** @return false if this message was stored as a statement before (a redelivery) */
    public boolean insertStatement(StatementRow s) {
        return jdbc.sql("""
                        INSERT INTO recon.statement (id, message_id, ledger_account, servicer_bic, servicer_account, statement_ref,
                               msg_id, statement_date, currency, opening_booked, closing_booked, received_at)
                        VALUES (:id, :msg, :acct, :svc, :svcAcct, :ref, :msgId, :date, :ccy, :open, :close, :at)
                        ON CONFLICT (message_id) DO NOTHING
                        """)
                .param("id", s.id()).param("msg", s.messageId()).param("acct", s.account()).param("svc", s.servicerBic())
                .param("svcAcct", s.servicerAccount()).param("ref", s.statementRef()).param("msgId", s.msgId())
                .param("date", s.date()).param("ccy", s.currency()).param("open", s.opening()).param("close", s.closing())
                .param("at", ts(s.receivedAt())).update() == 1;
    }

    public Optional<StatementRow> statementByMessage(UUID messageId) {
        return jdbc.sql("SELECT * FROM recon.statement WHERE message_id = :m").param("m", messageId).query((rs, n) -> statement(rs)).optional();
    }

    /** Oldest first. */
    public List<StatementRow> statements(String account) {
        return jdbc.sql("SELECT * FROM recon.statement WHERE ledger_account = :a ORDER BY statement_date, received_at")
                .param("a", account).query((rs, n) -> statement(rs)).list();
    }

    private static StatementRow statement(ResultSet rs) throws SQLException {
        return new StatementRow((UUID) rs.getObject("id"), (UUID) rs.getObject("message_id"), rs.getString("ledger_account"),
                rs.getString("servicer_bic"), rs.getString("servicer_account"), rs.getString("statement_ref"), rs.getString("msg_id"),
                rs.getObject("statement_date", LocalDate.class), rs.getString("currency"), rs.getBigDecimal("opening_booked"),
                rs.getBigDecimal("closing_booked"), instant(rs, "received_at"));
    }

    public record EntryRow(long id, UUID statementId, int seq, String entryRef, BigDecimal signed, Instant bookedAt,
                           LocalDate valueDate, String servicerRef, boolean reversal, String info, String bankTxCode,
                           String uetr, String endToEndId, String txs, String xml, LocalDate statementDate) {
    }

    public void insertEntry(EntryRow e) {
        jdbc.sql("""
                        INSERT INTO recon.statement_entry (statement_id, seq, entry_ref, signed_amount, booked_at, value_date,
                               servicer_ref, reversal, info, bank_tx_code, uetr, end_to_end_id, txs, xml)
                        VALUES (:st, :seq, :ref, :amt, :booked, :val, :svc, :rev, :info, :code, :uetr, :e2e, CAST(:txs AS jsonb), :xml)
                        """)
                .param("st", e.statementId()).param("seq", e.seq()).param("ref", e.entryRef()).param("amt", e.signed())
                .param("booked", ts(e.bookedAt())).param("val", e.valueDate()).param("svc", e.servicerRef()).param("rev", e.reversal())
                .param("info", e.info()).param("code", e.bankTxCode()).param("uetr", e.uetr()).param("e2e", e.endToEndId())
                .param("txs", e.txs()).param("xml", e.xml()).update();
    }

    private static final String ENTRY_SELECT = """
            SELECT e.*, e.txs::text AS txs_text, s.statement_date FROM recon.statement_entry e
            JOIN recon.statement s ON s.id = e.statement_id
            """;

    public List<EntryRow> entries(String account) {
        return jdbc.sql(ENTRY_SELECT + " WHERE s.ledger_account = :a ORDER BY s.statement_date, e.seq").param("a", account)
                .query((rs, n) -> entry(rs)).list();
    }

    public List<EntryRow> unmatchedEntries(String account) {
        return jdbc.sql(ENTRY_SELECT + """
                         WHERE s.ledger_account = :a AND NOT EXISTS
                           (SELECT 1 FROM recon.match_item i WHERE i.side = 'STATEMENT' AND i.item_id = e.id)
                         ORDER BY s.statement_date, e.seq
                        """).param("a", account).query((rs, n) -> entry(rs)).list();
    }

    /** UETR + amount of the single-payment statement entries already matched (to recognise duplicates). */
    public List<String[]> matchedStatementPayments(String account) {
        return jdbc.sql(ENTRY_SELECT + """
                         WHERE s.ledger_account = :a AND e.uetr IS NOT NULL AND EXISTS
                           (SELECT 1 FROM recon.match_item i WHERE i.side = 'STATEMENT' AND i.item_id = e.id)
                        """).param("a", account)
                .query((rs, n) -> new String[] {rs.getString("uetr"), rs.getBigDecimal("signed_amount").toPlainString()}).list();
    }

    private static EntryRow entry(ResultSet rs) throws SQLException {
        return new EntryRow(rs.getLong("id"), (UUID) rs.getObject("statement_id"), rs.getInt("seq"), rs.getString("entry_ref"),
                rs.getBigDecimal("signed_amount"), instant(rs, "booked_at"), rs.getObject("value_date", LocalDate.class),
                rs.getString("servicer_ref"), rs.getBoolean("reversal"), rs.getString("info"), rs.getString("bank_tx_code"),
                rs.getString("uetr"), rs.getString("end_to_end_id"), rs.getString("txs_text"), rs.getString("xml"),
                rs.getObject("statement_date", LocalDate.class));
    }

    // ------------------------------------------------------------------ ledger items

    public record ItemRow(long postingId, String account, UUID journalEntryId, LocalDate date, Instant bookedAt, BigDecimal signed,
                          String entryType, UUID paymentId, String uetr, String endToEndId, String narrative) {
    }

    public void insertItem(ItemRow i) {
        jdbc.sql("""
                        INSERT INTO recon.ledger_item (posting_id, ledger_account, journal_entry_id, item_date, booked_at, signed_amount,
                               entry_type, payment_id, uetr, end_to_end_id, narrative)
                        VALUES (:id, :acct, :je, :date, :booked, :amt, :type, :pay, :uetr, :e2e, :narr)
                        ON CONFLICT (posting_id) DO NOTHING
                        """)
                .param("id", i.postingId()).param("acct", i.account()).param("je", i.journalEntryId()).param("date", i.date())
                .param("booked", ts(i.bookedAt())).param("amt", i.signed()).param("type", i.entryType()).param("pay", i.paymentId())
                .param("uetr", i.uetr()).param("e2e", i.endToEndId()).param("narr", i.narrative()).update();
    }

    public boolean hasItem(long postingId) {
        return jdbc.sql("SELECT count(*) FROM recon.ledger_item WHERE posting_id = :id").param("id", postingId).query(Long.class).single() > 0;
    }

    public List<ItemRow> items(String account) {
        return jdbc.sql("SELECT * FROM recon.ledger_item WHERE ledger_account = :a ORDER BY booked_at, posting_id").param("a", account)
                .query((rs, n) -> item(rs)).list();
    }

    public List<ItemRow> unmatchedItems(String account) {
        return jdbc.sql("""
                        SELECT * FROM recon.ledger_item l WHERE ledger_account = :a AND NOT EXISTS
                          (SELECT 1 FROM recon.match_item i WHERE i.side = 'LEDGER' AND i.item_id = l.posting_id)
                        ORDER BY booked_at, posting_id
                        """).param("a", account).query((rs, n) -> item(rs)).list();
    }

    private static ItemRow item(ResultSet rs) throws SQLException {
        return new ItemRow(rs.getLong("posting_id"), rs.getString("ledger_account"), (UUID) rs.getObject("journal_entry_id"),
                rs.getObject("item_date", LocalDate.class), instant(rs, "booked_at"), rs.getBigDecimal("signed_amount"),
                rs.getString("entry_type"), (UUID) rs.getObject("payment_id"), rs.getString("uetr"), rs.getString("end_to_end_id"),
                rs.getString("narrative"));
    }

    // ------------------------------------------------------------------ matches

    public record MatchRow(long id, String account, String rule, String matchedBy, String comment, Instant matchedAt) {
    }

    public record MatchItemRow(long matchId, String side, long itemId, BigDecimal signed) {
    }

    public long insertMatch(String account, String rule, String by, String comment, Instant at) {
        return jdbc.sql("""
                        INSERT INTO recon.match (ledger_account, rule, matched_by, comment, matched_at)
                        VALUES (:a, :rule, :by, :comment, :at) RETURNING id
                        """)
                .param("a", account).param("rule", rule).param("by", by).param("comment", comment).param("at", ts(at))
                .query(Long.class).single();
    }

    public void insertMatchItem(long matchId, String side, long itemId, BigDecimal signed) {
        jdbc.sql("INSERT INTO recon.match_item (match_id, side, item_id, signed_amount) VALUES (:m, :s, :i, :amt)")
                .param("m", matchId).param("s", side).param("i", itemId).param("amt", signed).update();
    }

    public List<MatchRow> matches(String account) {
        return jdbc.sql("SELECT * FROM recon.match WHERE ledger_account = :a ORDER BY id").param("a", account)
                .query((rs, n) -> new MatchRow(rs.getLong("id"), rs.getString("ledger_account"), rs.getString("rule"),
                        rs.getString("matched_by"), rs.getString("comment"), instant(rs, "matched_at"))).list();
    }

    public List<MatchItemRow> matchItems(String account) {
        return jdbc.sql("""
                        SELECT i.* FROM recon.match_item i JOIN recon.match m ON m.id = i.match_id
                        WHERE m.ledger_account = :a ORDER BY i.match_id
                        """).param("a", account)
                .query((rs, n) -> new MatchItemRow(rs.getLong("match_id"), rs.getString("side"), rs.getLong("item_id"),
                        rs.getBigDecimal("signed_amount"))).list();
    }


    // ------------------------------------------------------------------ breaks

    public record BreakRow(String id, String account, String type, Long ledgerItemId, Long statementEntryId, BigDecimal difference,
                           String status, Instant openedAt, String resolution, String comment, String resolvedBy,
                           Instant resolvedAt, Long clearedByMatch) {
    }

    public String insertBreak(String account, String type, Long ledgerItemId, Long statementEntryId, BigDecimal difference, Instant at) {
        String id = jdbc.sql("SELECT 'BRK-' || lpad(nextval('recon.break_seq')::text, 4, '0')").query(String.class).single();
        jdbc.sql("""
                        INSERT INTO recon.recon_break (id, ledger_account, type, ledger_item_id, statement_entry_id, difference,
                               status, opened_at)
                        VALUES (:id, :a, :type, :l, :s, :diff, 'OPEN', :at)
                        """)
                .param("id", id).param("a", account).param("type", type).param("l", ledgerItemId).param("s", statementEntryId)
                .param("diff", difference).param("at", ts(at)).update();
        return id;
    }

    /** Open and resolved breaks: not cleared by a match. */
    public List<BreakRow> activeBreaks(String account) {
        return jdbc.sql("SELECT * FROM recon.recon_break WHERE ledger_account = :a AND status <> 'CLEARED' ORDER BY id")
                .param("a", account).query((rs, n) -> breakRow(rs)).list();
    }

    public List<BreakRow> breaks(String account) {
        return jdbc.sql("SELECT * FROM recon.recon_break WHERE ledger_account = :a ORDER BY id").param("a", account)
                .query((rs, n) -> breakRow(rs)).list();
    }

    public Optional<BreakRow> lockBreak(String id) {
        return jdbc.sql("SELECT * FROM recon.recon_break WHERE id = :id FOR UPDATE").param("id", id).query((rs, n) -> breakRow(rs)).optional();
    }

    public Optional<BreakRow> findBreak(String id) {
        return jdbc.sql("SELECT * FROM recon.recon_break WHERE id = :id").param("id", id).query((rs, n) -> breakRow(rs)).optional();
    }

    public void resolve(String id, String resolution, String comment, String by, Instant at) {
        jdbc.sql("""
                        UPDATE recon.recon_break SET status = 'RESOLVED', resolution = :r, comment = :c, resolved_by = :by, resolved_at = :at
                        WHERE id = :id
                        """).param("id", id).param("r", resolution).param("c", comment).param("by", by).param("at", ts(at)).update();
    }

    public void reopen(String id) {
        jdbc.sql("""
                        UPDATE recon.recon_break SET status = 'OPEN', resolution = NULL, comment = NULL, resolved_by = NULL,
                               resolved_at = NULL WHERE id = :id
                        """).param("id", id).update();
    }

    public void clear(String id, long matchId) {
        jdbc.sql("UPDATE recon.recon_break SET status = 'CLEARED', cleared_by_match = :m WHERE id = :id")
                .param("id", id).param("m", matchId).update();
    }

    private static BreakRow breakRow(ResultSet rs) throws SQLException {
        return new BreakRow(rs.getString("id"), rs.getString("ledger_account"), rs.getString("type"),
                (Long) rs.getObject("ledger_item_id"), (Long) rs.getObject("statement_entry_id"), rs.getBigDecimal("difference"),
                rs.getString("status"), instant(rs, "opened_at"), rs.getString("resolution"), rs.getString("comment"),
                rs.getString("resolved_by"), instant(rs, "resolved_at"), (Long) rs.getObject("cleared_by_match"));
    }

    // ------------------------------------------------------------------ locks and idempotency

    /** One reconciliation of an account at a time: matching reads "unmatched" and then writes matches. */
    public void lockAccount(String account) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('recon:' || :a))").param("a", account).query().singleRow();
    }

    public void lockKey(String key) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('recon-key:' || :k))").param("k", key).query().singleRow();
    }

    public record StoredKey(String hash, String result) {
    }

    public Optional<StoredKey> key(String key) {
        return jdbc.sql("SELECT request_hash, result FROM recon.command_key WHERE idempotency_key = :k").param("k", key)
                .query((rs, n) -> new StoredKey(rs.getString(1), rs.getString(2))).optional();
    }

    public void saveKey(String key, String hash, String result, Instant at) {
        jdbc.sql("INSERT INTO recon.command_key (idempotency_key, request_hash, result, created_at) VALUES (:k, :h, :r, :at)")
                .param("k", key).param("h", hash).param("r", result).param("at", ts(at)).update();
    }

    public static <T> List<T> safe(Collection<T> c) {
        return c == null ? List.of() : List.copyOf(c);
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
