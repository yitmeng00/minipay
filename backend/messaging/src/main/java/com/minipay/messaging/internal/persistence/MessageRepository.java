package com.minipay.messaging.internal.persistence;

import com.minipay.common.money.Money;
import com.minipay.messaging.api.MessageDetail;
import com.minipay.messaging.api.MessageDirection;
import com.minipay.messaging.api.MessageType;
import com.minipay.messaging.api.MessageView;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for the {@code messaging} schema. Rows are inserted once and never changed (trigger-enforced). */
@Repository
public class MessageRepository {

    private static final String COLUMNS = """
            id, direction, message_type, msg_id, sender_bic, receiver_bic, uetr, end_to_end_id, instruction_id,
            original_msg_id, account, amount, currency, settlement_date, status, reason_code, errors, raw_sha256, created_at
            """;

    private final JdbcClient jdbc;

    public MessageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Everything stored about one message. */
    public record NewMessage(MessageDirection direction, MessageType type, String msgId, String senderBic,
                             String receiverBic, String businessKey, String uetr, String endToEndId, String instructionId,
                             String originalMsgId, String account, Money amount, LocalDate settlementDate, String status,
                             String reasonCode, List<String> errors, String raw) {
    }

    public MessageView insert(NewMessage m) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO messaging.message (id, direction, message_type, syntax, msg_id, sender_bic, receiver_bic,
                               business_key, uetr, end_to_end_id, instruction_id, original_msg_id, account, amount, currency,
                               settlement_date, status, reason_code, errors, raw, raw_sha256)
                        VALUES (:id, :dir, :type, :syntax, :msgId, :sender, :receiver, :key, :uetr, :e2e, :instr, :orig,
                                :acct, :amount, :ccy, :date, :status, :reason, :errors, :raw, :sha)
                        """)
                .param("id", id).param("dir", m.direction().name())
                .param("type", m.type() == null ? null : m.type().identifier())
                .param("syntax", m.type() == null ? null : m.type().isMx() ? "MX" : "MT")
                .param("msgId", m.msgId()).param("sender", m.senderBic()).param("receiver", m.receiverBic())
                .param("key", m.businessKey())
                .param("uetr", m.uetr() == null ? null : safeUuid(m.uetr()))
                .param("e2e", m.endToEndId()).param("instr", m.instructionId()).param("orig", m.originalMsgId())
                .param("acct", m.account())
                .param("amount", m.amount() == null ? null : m.amount().amount())
                .param("ccy", m.amount() == null ? null : m.amount().currencyCode())
                .param("date", m.settlementDate()).param("status", m.status()).param("reason", m.reasonCode())
                .param("errors", m.errors().toArray(new String[0]))
                .param("raw", m.raw()).param("sha", sha256(m.raw()))
                .update();
        return find(id).orElseThrow().message();
    }

    public Optional<MessageDetail> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + ", raw FROM messaging.message WHERE id = :id").param("id", id)
                .query((rs, n) -> new MessageDetail(view(rs), rs.getString("raw"))).optional();
    }

    public Optional<MessageView> findByBusinessKey(String key) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM messaging.message WHERE business_key = :k").param("k", key)
                .query((rs, n) -> view(rs)).optional();
    }

    public Optional<MessageView> findBySender(MessageDirection direction, String senderBic, String msgId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM messaging.message WHERE direction = :d AND sender_bic = :s AND msg_id = :m")
                .param("d", direction.name()).param("s", senderBic).param("m", msgId)
                .query((rs, n) -> view(rs)).optional();
    }

    public List<MessageView> forUetr(UUID uetr) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM messaging.message WHERE uetr = :u ORDER BY created_at, id")
                .param("u", uetr).query((rs, n) -> view(rs)).list();
    }

    public List<MessageView> recent(MessageType type, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM messaging.message WHERE (:t::text IS NULL OR message_type = :t) "
                        + "ORDER BY created_at DESC LIMIT :n")
                .param("t", type == null ? null : type.identifier()).param("n", limit)
                .query((rs, n) -> view(rs)).list();
    }

    /** How many statements have been issued for an account: the next ElctrncSeqNb is this + 1. */
    public long statementsIssued(String account) {
        return jdbc.sql("SELECT count(*) FROM messaging.message WHERE message_type = 'camt.053.001.08' AND direction = 'OUTBOUND' AND account = :a")
                .param("a", account).query(Long.class).single();
    }

    public long nextSequence() {
        return jdbc.sql("SELECT nextval('messaging.msg_seq')").query(Long.class).single();
    }

    // ------------------------------------------------------------------ idempotency

    public void lockIdempotencyKey(String key) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:k, 0))").param("k", "messaging:" + key).query().singleRow();
    }

    public record StoredKey(String requestHash, UUID messageId) {
    }

    public Optional<StoredKey> findIdempotencyKey(String key) {
        return jdbc.sql("SELECT request_hash, message_id FROM messaging.idempotency_key WHERE idempotency_key = :k")
                .param("k", key).query((rs, n) -> new StoredKey(rs.getString(1), rs.getObject(2, UUID.class))).optional();
    }

    public void insertIdempotencyKey(String key, String hash, UUID messageId) {
        jdbc.sql("INSERT INTO messaging.idempotency_key (idempotency_key, request_hash, message_id) VALUES (:k, :h, :m)")
                .param("k", key).param("h", hash).param("m", messageId).update();
    }

    // ------------------------------------------------------------------ helpers

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static UUID safeUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null; // an invalid UETR in an inbound message: stored in the raw text, reported in errors
        }
    }

    private static MessageView view(ResultSet rs) throws SQLException {
        String type = rs.getString("message_type");
        BigDecimal amount = rs.getBigDecimal("amount");
        String ccy = rs.getString("currency");
        Array errors = rs.getArray("errors");
        UUID uetr = rs.getObject("uetr", UUID.class);
        return new MessageView(
                rs.getObject("id", UUID.class),
                MessageDirection.valueOf(rs.getString("direction")),
                type == null ? null : MessageType.fromIdentifier(type),
                rs.getString("msg_id"),
                trim(rs.getString("sender_bic")),
                trim(rs.getString("receiver_bic")),
                uetr == null ? null : uetr.toString(),
                rs.getString("end_to_end_id"),
                rs.getString("instruction_id"),
                rs.getString("original_msg_id"),
                rs.getString("account"),
                amount == null ? null : Money.of(amount, ccy.trim()),
                rs.getObject("settlement_date", LocalDate.class),
                rs.getString("status"),
                rs.getString("reason_code"),
                errors == null ? List.of() : Arrays.asList((String[]) errors.getArray()),
                rs.getString("raw_sha256"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
