package com.minipay.network.internal.tracker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.common.money.Money;
import com.minipay.messaging.api.Charge;
import com.minipay.network.api.TrackedMessage;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TrackerRepository {

    private static final TypeReference<List<Map<String, String>>> CHARGES = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public TrackerRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Idempotent: a redelivered event (same id) is recorded once. @return true if it was new */
    public boolean record(TrackedMessage m, UUID uetr) {
        String charges;
        try {
            charges = json.writeValueAsString(m.charges().stream()
                    .map(c -> Map.of("agent", c.agentBic(), "amount", c.amount().amount().toPlainString(),
                            "currency", c.amount().currencyCode())).toList());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return jdbc.sql("""
                        INSERT INTO network.tracker_event (event_id, uetr, message_type, msg_id, sender_bic, receiver_bic,
                               original_msg_id, amount, currency, settlement_method, status, reason_code, charges, sent_at)
                        VALUES (:id, :uetr, :type, :msgId, :from, :to, :orig, :amount, :ccy, :method, :status, :reason,
                                CAST(:charges AS jsonb), :sentAt)
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .param("id", m.eventId()).param("uetr", uetr).param("type", m.type()).param("msgId", m.msgId())
                .param("from", m.fromBic()).param("to", m.toBic()).param("orig", m.originalMsgId())
                .param("amount", m.amount() == null ? null : m.amount().amount())
                .param("ccy", m.amount() == null ? null : m.amount().currencyCode())
                .param("method", m.settlementMethod()).param("status", m.status()).param("reason", m.reasonCode())
                .param("charges", charges).param("sentAt", Timestamp.from(m.sentAt()))
                .update() == 1;
    }

    public List<TrackedMessage> forUetr(UUID uetr) {
        return jdbc.sql("SELECT * FROM network.tracker_event WHERE uetr = :u ORDER BY sent_at, recorded_at")
                .param("u", uetr).query((rs, n) -> row(rs)).list();
    }

    /** UETRs by most recent activity. */
    public List<UUID> recentUetrs(int limit) {
        return jdbc.sql("SELECT uetr FROM network.tracker_event GROUP BY uetr ORDER BY max(sent_at) DESC LIMIT :n")
                .param("n", limit).query(UUID.class).list();
    }

    private TrackedMessage row(ResultSet rs) throws SQLException {
        BigDecimal amount = rs.getBigDecimal("amount");
        String ccy = rs.getString("currency");
        List<Charge> charges;
        try {
            charges = json.readValue(rs.getString("charges"), CHARGES).stream()
                    .map(c -> new Charge(Money.of(new BigDecimal(c.get("amount")), c.get("currency")), c.get("agent"))).toList();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new TrackedMessage(rs.getObject("event_id", UUID.class), rs.getString("message_type"), rs.getString("msg_id"),
                rs.getString("sender_bic").trim(), rs.getString("receiver_bic").trim(), rs.getString("original_msg_id"),
                amount == null ? null : Money.of(amount, ccy.trim()), rs.getString("settlement_method"),
                rs.getString("status"), rs.getString("reason_code"), charges, rs.getTimestamp("sent_at").toInstant());
    }
}
