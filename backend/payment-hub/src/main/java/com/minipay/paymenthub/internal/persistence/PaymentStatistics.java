package com.minipay.paymenthub.internal.persistence;

import com.minipay.common.money.Money;
import com.minipay.paymenthub.api.DailyOverview;
import com.minipay.paymenthub.api.PaymentStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Aggregates for the dashboard, in plain SQL: GROUP BY over a day is what a database is for, and
 * there is nothing to map back into entities.
 *
 * <p>{@code created_at} is stored in UTC. A "day" and an "hour" are the bank's, so timestamps are
 * converted with {@code AT TIME ZONE} before they are bucketed: a payment created at 23:30 UTC belongs
 * to the next day in Kuala Lumpur.
 */
@Repository
public class PaymentStatistics {

    private static final String SENT_OR_LATER = "('SENT', 'ACKED', 'SETTLED', 'RETURNED')";
    private static final String NOT_YET_SENT = "('RECEIVED', 'VALIDATED', 'SCREENED', 'HELD_COMPLIANCE', 'FUNDS_RESERVED')";

    private final JdbcClient jdbc;

    public PaymentStatistics(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public DailyOverview overview(LocalDate date, ZoneId zone, Instant now, Duration stuckAfter) {
        String tz = zone.getId();
        String onDate = "(created_at AT TIME ZONE :tz)::date = :date";

        Map<PaymentStatus, Long> byStatus = new EnumMap<>(PaymentStatus.class);
        for (PaymentStatus s : PaymentStatus.values()) {
            byStatus.put(s, 0L);
        }
        jdbc.sql("SELECT status, count(*) FROM payment.payment WHERE " + onDate + " GROUP BY status")
                .param("tz", tz).param("date", date)
                .query(rs -> {
                    byStatus.put(PaymentStatus.valueOf(rs.getString(1)), rs.getLong(2));
                });
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();

        Long[] hours = new Long[24];
        Arrays.fill(hours, 0L);
        jdbc.sql("SELECT extract(hour FROM created_at AT TIME ZONE :tz)::int, count(*) FROM payment.payment WHERE "
                        + onDate + " GROUP BY 1")
                .param("tz", tz).param("date", date)
                .query(rs -> {
                    hours[rs.getInt(1)] = rs.getLong(2);
                });

        List<Money> valueSent = jdbc.sql("SELECT currency, sum(interbank_settlement_amount) FROM payment.payment WHERE "
                        + onDate + " AND status IN " + SENT_OR_LATER + " GROUP BY currency ORDER BY currency")
                .param("tz", tz).param("date", date)
                .query((rs, n) -> Money.of(rs.getBigDecimal(2), rs.getString(1).trim())).list();

        long stuck = jdbc.sql("SELECT count(*) FROM payment.payment WHERE status = 'SENT' AND updated_at < :before")
                .param("before", java.sql.Timestamp.from(now.minus(stuckAfter))).query(Long.class).single();

        List<DailyOverview.ReasonCount> reasons = jdbc.sql("""
                        SELECT rejection_reason, count(*) FROM payment.payment
                         WHERE (created_at AT TIME ZONE :tz)::date = :date AND status = 'REJECTED'
                         GROUP BY rejection_reason ORDER BY count(*) DESC, rejection_reason
                        """)
                .param("tz", tz).param("date", date)
                .query((rs, n) -> new DailyOverview.ReasonCount(rs.getString(1), rs.getLong(2))).list();

        List<DailyOverview.CurrencyQueue> queued = new ArrayList<>(jdbc.sql(
                        "SELECT currency, count(*), sum(amount) FROM payment.payment WHERE status IN " + NOT_YET_SENT
                                + " AND coalesce(execution_date, requested_exec_date) <= :date GROUP BY currency ORDER BY currency")
                .param("date", date)
                .query((rs, n) -> new DailyOverview.CurrencyQueue(rs.getString(1).trim(), rs.getLong(2),
                        Money.of(rs.getBigDecimal(3), rs.getString(1).trim())))
                .list());

        long warehoused = jdbc.sql("SELECT count(*) FROM payment.payment WHERE status = 'FUNDS_RESERVED' AND execution_date > :date")
                .param("date", date).query(Long.class).single();

        return new DailyOverview(date, tz, total, byStatus, List.of(hours), valueSent, stuck, (int) stuckAfter.toMinutes(),
                reasons, queued, warehoused);
    }
}
