package com.minipay.paymenthub.api;

import com.minipay.common.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * One business day at a glance, for the operations dashboard. "Today" and the hours are in the bank's
 * time zone; amounts are per currency (there is no FX conversion in the simulator).
 *
 * @param byStatus        payments created on the date, by current status
 * @param perHour         payments created in each local hour 00–23
 * @param valueSent       interbank amount of the date's payments that have left the bank (SENT or later)
 * @param stuckInSent     payments of any date still SENT after {@code stuckAfterMinutes} without an acknowledgement
 * @param rejectedByReason the date's rejections, most frequent reason first
 * @param queued          per currency: payments due on or before the date that have not been sent yet
 * @param warehoused      payments reserved for a later execution date
 */
public record DailyOverview(
        LocalDate date,
        String timeZone,
        long total,
        Map<PaymentStatus, Long> byStatus,
        List<Long> perHour,
        List<Money> valueSent,
        long stuckInSent,
        int stuckAfterMinutes,
        List<ReasonCount> rejectedByReason,
        List<CurrencyQueue> queued,
        long warehoused) {

    public record ReasonCount(String reasonCode, long count) {
    }

    public record CurrencyQueue(String currency, long count, Money amount) {
    }
}
