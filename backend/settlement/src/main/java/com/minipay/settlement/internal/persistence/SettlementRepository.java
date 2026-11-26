package com.minipay.settlement.internal.persistence;

import com.minipay.common.money.Money;
import com.minipay.settlement.api.TransferStatus;
import com.minipay.settlement.api.TransferView;
import com.minipay.settlement.api.Views;
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

/** The settlement schema, in plain SQL: the locks are the point (ADR-008 §5). */
@Repository
public class SettlementRepository {

    private final JdbcClient jdbc;

    public SettlementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ participants

    public record ParticipantRow(String bic, String name, boolean simulated, String rtgsAccount, String instantAccount) {
    }

    public void upsertParticipant(ParticipantRow p) {
        jdbc.sql("""
                        INSERT INTO settlement.participant (bic, name, simulated, rtgs_account, instant_account)
                        VALUES (:bic, :name, :sim, :rtgs, :inst)
                        ON CONFLICT (bic) DO UPDATE SET name = EXCLUDED.name, simulated = EXCLUDED.simulated
                        """)
                .param("bic", p.bic()).param("name", p.name()).param("sim", p.simulated())
                .param("rtgs", p.rtgsAccount()).param("inst", p.instantAccount()).update();
    }

    public List<ParticipantRow> participants() {
        return jdbc.sql("SELECT * FROM settlement.participant ORDER BY bic").query((rs, n) -> participant(rs)).list();
    }

    public Optional<ParticipantRow> participant(String bic) {
        return jdbc.sql("SELECT * FROM settlement.participant WHERE bic = :bic").param("bic", bic)
                .query((rs, n) -> participant(rs)).optional();
    }

    /**
     * Locks participants in BIC order. Every debit of a participant's settlement accounts happens under its lock,
     * so "is there enough?" and "take it" cannot be separated by another payment; the fixed order means two
     * transactions that lock several participants (a DNS settlement) never deadlock.
     */
    public void lockParticipants(Collection<String> bics) {
        if (bics.isEmpty()) {
            return;
        }
        jdbc.sql("SELECT bic FROM settlement.participant WHERE bic IN (:bics) ORDER BY bic FOR UPDATE")
                .param("bics", bics).query(String.class).list();
    }

    private static ParticipantRow participant(ResultSet rs) throws SQLException {
        return new ParticipantRow(rs.getString("bic"), rs.getString("name"), rs.getBoolean("simulated"),
                rs.getString("rtgs_account"), rs.getString("instant_account"));
    }

    // ------------------------------------------------------------------ transfers

    public record NewTransfer(UUID id, String system, String kind, String messageType, UUID messageId, String msgId,
                              UUID uetr, String endToEndId, String instructionId, String payerBic, String payeeBic,
                              Money amount, String priority, LocalDate settlementDate, TransferStatus status,
                              String cycleId, Instant deadline, String reasonCode, String reasonDetail, Instant receivedAt) {
    }

    public void insert(NewTransfer t) {
        jdbc.sql("""
                        INSERT INTO settlement.transfer (id, system, kind, message_type, message_id, msg_id, uetr, end_to_end_id,
                               instruction_id, payer_bic, payee_bic, amount, currency, priority, settlement_date, status,
                               cycle_id, deadline, reason_code, reason_detail, received_at, updated_at)
                        VALUES (:id, :system, :kind, :type, :msg, :msgId, :uetr, :e2e, :instr, :payer, :payee, :amount, :ccy,
                                :prio, :date, :status, :cycle, :deadline, :reason, :detail, :at, :at)
                        """)
                .param("id", t.id()).param("system", t.system()).param("kind", t.kind()).param("type", t.messageType())
                .param("msg", t.messageId()).param("msgId", t.msgId()).param("uetr", t.uetr()).param("e2e", t.endToEndId())
                .param("instr", t.instructionId()).param("payer", t.payerBic()).param("payee", t.payeeBic())
                .param("amount", t.amount().amount()).param("ccy", t.amount().currencyCode()).param("prio", t.priority())
                .param("date", t.settlementDate()).param("status", t.status().name()).param("cycle", t.cycleId())
                .param("deadline", ts(t.deadline())).param("reason", t.reasonCode()).param("detail", t.reasonDetail())
                .param("at", ts(t.receivedAt()))
                .update();
    }

    public Optional<TransferView> find(UUID id) {
        return jdbc.sql("SELECT * FROM settlement.transfer WHERE id = :id").param("id", id).query((rs, n) -> transfer(rs)).optional();
    }

    public Optional<TransferView> lock(UUID id) {
        return jdbc.sql("SELECT * FROM settlement.transfer WHERE id = :id FOR UPDATE").param("id", id)
                .query((rs, n) -> transfer(rs)).optional();
    }

    public Optional<TransferView> findByMessage(UUID messageId) {
        return jdbc.sql("SELECT * FROM settlement.transfer WHERE message_id = :m").param("m", messageId)
                .query((rs, n) -> transfer(rs)).optional();
    }

    public List<TransferView> forUetr(UUID uetr) {
        return jdbc.sql("SELECT * FROM settlement.transfer WHERE uetr = :u ORDER BY received_at").param("u", uetr)
                .query((rs, n) -> transfer(rs)).list();
    }

    /** A participant's queued RTGS payments, in queue order within each priority. */
    public List<TransferView> rtgsQueue(String payerBic) {
        return jdbc.sql("""
                        SELECT * FROM settlement.transfer WHERE payer_bic = :p AND kind = 'RTGS' AND status = 'QUEUED'
                        ORDER BY (priority <> 'HIGH'), queue_seq
                        """).param("p", payerBic).query((rs, n) -> transfer(rs)).list();
    }

    /** Payers with something queued (the sweeper retries their queues). */
    public List<String> payersWithQueue() {
        return jdbc.sql("SELECT DISTINCT payer_bic FROM settlement.transfer WHERE kind = 'RTGS' AND status = 'QUEUED' ORDER BY 1")
                .query(String.class).list();
    }

    public List<TransferView> queuedTo(String payeeBic) {
        return jdbc.sql("SELECT * FROM settlement.transfer WHERE payee_bic = :p AND kind = 'RTGS' AND status = 'QUEUED' ORDER BY queue_seq")
                .param("p", payeeBic).query((rs, n) -> transfer(rs)).list();
    }

    public List<TransferView> queuedDueBy(LocalDate date) {
        return jdbc.sql("SELECT * FROM settlement.transfer WHERE kind = 'RTGS' AND status = 'QUEUED' AND settlement_date <= :d ORDER BY queue_seq")
                .param("d", date).query((rs, n) -> transfer(rs)).list();
    }

    public List<TransferView> inCycle(String cycleId) {
        return jdbc.sql("SELECT * FROM settlement.transfer WHERE cycle_id = :c ORDER BY received_at").param("c", cycleId)
                .query((rs, n) -> transfer(rs)).list();
    }

    /** Pending instant payments whose deadline passed, or that a simulated participant should answer now. */
    public List<UUID> instantDue(Instant now) {
        return jdbc.sql("""
                        SELECT id FROM settlement.transfer WHERE kind = 'INSTANT' AND status = 'PENDING'
                          AND (deadline <= :now OR auto_answer_at <= :now) ORDER BY deadline
                        """).param("now", ts(now)).query(UUID.class).list();
    }

    /** Transfers a participant sent or received since a moment, newest first. */
    public List<TransferView> recent(String kind, String bic, Instant since, int limit) {
        return jdbc.sql("""
                        SELECT * FROM settlement.transfer WHERE kind = :kind AND (payer_bic = :bic OR payee_bic = :bic)
                          AND received_at >= :since ORDER BY received_at DESC LIMIT :limit
                        """).param("kind", kind).param("bic", bic).param("since", ts(since)).param("limit", limit)
                .query((rs, n) -> transfer(rs)).list();
    }

    public void updateStatus(UUID id, TransferStatus status, String reasonCode, String reasonDetail, UUID entryId, Instant at) {
        jdbc.sql("""
                        UPDATE settlement.transfer SET status = :s, reason_code = :code, reason_detail = :detail,
                               entry_id = COALESCE(:entry, entry_id), settled_at = CASE WHEN :s = 'SETTLED' THEN :at ELSE settled_at END,
                               updated_at = :at
                        WHERE id = :id
                        """)
                .param("id", id).param("s", status.name()).param("code", reasonCode).param("detail", reasonDetail)
                .param("entry", entryId).param("at", ts(at)).update();
    }

    public void reserved(UUID id, UUID reservationId, Instant autoAnswerAt, Instant at) {
        jdbc.sql("UPDATE settlement.transfer SET reservation_id = :r, auto_answer_at = :auto, updated_at = :at WHERE id = :id")
                .param("id", id).param("r", reservationId).param("auto", ts(autoAnswerAt)).param("at", ts(at)).update();
    }

    public Optional<UUID> reservationOf(UUID id) {
        return jdbc.sql("SELECT reservation_id FROM settlement.transfer WHERE id = :id").param("id", id)
                .query((rs, n) -> (UUID) rs.getObject(1)).optional().filter(java.util.Objects::nonNull);
    }

    public void reorder(UUID id, long seq, String priority, Instant at) {
        jdbc.sql("UPDATE settlement.transfer SET queue_seq = :seq, priority = :p, updated_at = :at WHERE id = :id")
                .param("id", id).param("seq", seq).param("p", priority).param("at", ts(at)).update();
    }

    public void event(UUID transferId, TransferStatus from, TransferStatus to, String detail, String actor) {
        jdbc.sql("""
                        INSERT INTO settlement.transfer_event (transfer_id, from_status, to_status, detail, actor)
                        VALUES (:id, :from, :to, :detail, :actor)
                        """)
                .param("id", transferId).param("from", from == null ? null : from.name()).param("to", to.name())
                .param("detail", detail.length() > 500 ? detail.substring(0, 500) : detail).param("actor", actor).update();
    }

    public List<Views.TransferEvent> events(UUID transferId) {
        return jdbc.sql("SELECT * FROM settlement.transfer_event WHERE transfer_id = :id ORDER BY id").param("id", transferId)
                .query((rs, n) -> new Views.TransferEvent(rs.getTimestamp("occurred_at").toInstant(),
                        rs.getString("from_status") == null ? null : TransferStatus.valueOf(rs.getString("from_status")),
                        TransferStatus.valueOf(rs.getString("to_status")), rs.getString("detail"), rs.getString("actor")))
                .list();
    }

    // ------------------------------------------------------------------ DNS cycles

    public record CycleRow(String id, String system, LocalDate businessDate, int seq, String status, Instant scheduledClose,
                           Instant openedAt, Instant settledAt, UUID entryId, String unwound) {
    }

    public Optional<CycleRow> openCycle(String system, LocalDate date) {
        return jdbc.sql("SELECT * FROM settlement.dns_cycle WHERE system = :s AND business_date = :d AND status = 'OPEN'")
                .param("s", system).param("d", date).query((rs, n) -> cycle(rs)).optional();
    }

    public int lastSeq(String system, LocalDate date) {
        return jdbc.sql("SELECT COALESCE(MAX(seq), 0) FROM settlement.dns_cycle WHERE system = :s AND business_date = :d")
                .param("s", system).param("d", date).query(Integer.class).single();
    }

    /** @return false if another cycle is already open for that system and date (a concurrent arrival opened it) */
    public boolean insertCycle(CycleRow c) {
        return jdbc.sql("""
                        INSERT INTO settlement.dns_cycle (id, system, business_date, seq, status, scheduled_close, opened_at)
                        VALUES (:id, :s, :d, :seq, 'OPEN', :close, :opened) ON CONFLICT DO NOTHING
                        """)
                .param("id", c.id()).param("s", c.system()).param("d", c.businessDate()).param("seq", c.seq())
                .param("close", ts(c.scheduledClose())).param("opened", ts(c.openedAt())).update() == 1;
    }

    public Optional<CycleRow> lockCycle(String id) {
        return jdbc.sql("SELECT * FROM settlement.dns_cycle WHERE id = :id FOR UPDATE").param("id", id)
                .query((rs, n) -> cycle(rs)).optional();
    }

    public Optional<CycleRow> findCycle(String id) {
        return jdbc.sql("SELECT * FROM settlement.dns_cycle WHERE id = :id").param("id", id).query((rs, n) -> cycle(rs)).optional();
    }

    public List<CycleRow> cyclesDue(Instant now) {
        return jdbc.sql("SELECT * FROM settlement.dns_cycle WHERE status = 'OPEN' AND scheduled_close <= :now ORDER BY scheduled_close")
                .param("now", ts(now)).query((rs, n) -> cycle(rs)).list();
    }

    public List<CycleRow> cycles(LocalDate date) {
        return jdbc.sql("SELECT * FROM settlement.dns_cycle WHERE business_date = :d ORDER BY seq DESC").param("d", date)
                .query((rs, n) -> cycle(rs)).list();
    }

    public void settleCycle(String id, Instant at, UUID entryId, String unwound) {
        jdbc.sql("UPDATE settlement.dns_cycle SET status = 'SETTLED', settled_at = :at, entry_id = :e, unwound = :u WHERE id = :id")
                .param("id", id).param("at", ts(at)).param("e", entryId).param("u", unwound).update();
    }

    public void insertPosition(String cycleId, String bic, BigDecimal pays, BigDecimal receives) {
        jdbc.sql("INSERT INTO settlement.dns_position (cycle_id, bic, pays, receives, net) VALUES (:c, :b, :p, :r, :n)")
                .param("c", cycleId).param("b", bic).param("p", pays).param("r", receives).param("n", receives.subtract(pays))
                .update();
    }

    private static CycleRow cycle(ResultSet rs) throws SQLException {
        return new CycleRow(rs.getString("id"), rs.getString("system"), rs.getObject("business_date", LocalDate.class),
                rs.getInt("seq"), rs.getString("status"), instant(rs, "scheduled_close"), instant(rs, "opened_at"),
                instant(rs, "settled_at"), (UUID) rs.getObject("entry_id"), rs.getString("unwound"));
    }

    // ------------------------------------------------------------------ idempotency of commands

    public void lockKey(String key) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('settlement:' || :k))").param("k", key).query().singleRow();
    }

    public Optional<String> keyHash(String key) {
        return jdbc.sql("SELECT request_hash FROM settlement.command_key WHERE idempotency_key = :k").param("k", key)
                .query(String.class).optional();
    }

    public void saveKey(String key, String hash, Instant at) {
        jdbc.sql("INSERT INTO settlement.command_key (idempotency_key, request_hash, created_at) VALUES (:k, :h, :at)")
                .param("k", key).param("h", hash).param("at", ts(at)).update();
    }

    // ------------------------------------------------------------------ mapping

    private static TransferView transfer(ResultSet rs) throws SQLException {
        return new TransferView((UUID) rs.getObject("id"), rs.getString("system"), rs.getString("kind"),
                rs.getString("message_type"), (UUID) rs.getObject("message_id"), rs.getString("msg_id"), (UUID) rs.getObject("uetr"),
                rs.getString("end_to_end_id"), rs.getString("instruction_id"),
                rs.getString("payer_bic"), rs.getString("payee_bic"),
                Money.of(rs.getBigDecimal("amount"), rs.getString("currency")), rs.getString("priority"),
                rs.getObject("settlement_date", LocalDate.class), TransferStatus.valueOf(rs.getString("status")),
                rs.getLong("queue_seq"), rs.getString("cycle_id"), instant(rs, "deadline"), rs.getString("reason_code"),
                rs.getString("reason_detail"), instant(rs, "received_at"), instant(rs, "settled_at"), null);
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
