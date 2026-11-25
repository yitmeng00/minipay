package com.minipay.refdata.internal.domain;

import com.minipay.refdata.api.SchemeView;
import com.minipay.refdata.api.ValueDating;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Picks the value date for a payment in one currency.
 *
 * <p>Start from the earliest acceptable date (for example the customer's requested execution date) and
 * walk forward until a day is (a) a business day in the currency's calendar and (b) still before
 * that day's cut-off. The cut-off is a wall-clock time in the <em>scheme's</em> time zone, turned into
 * an instant for that particular date, so it is right on both sides of a daylight-saving change: the
 * 17:00 Frankfurt cut-off is 00:00 the next day in Kuala Lumpur in winter, and 23:00 the same day in summer.
 */
public final class ValueDater {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private ValueDater() {
    }

    public static ValueDating valueDate(SchemeView scheme, BusinessCalendar calendar, LocalDate earliest, Instant now) {
        ZoneId zone = ZoneId.of(scheme.timeZone());
        List<String> reasons = new ArrayList<>();
        LocalDate d = earliest;
        for (int i = 0; i < 31; i++, d = d.plusDays(1)) {
            String closed = calendar.whyClosed(d);
            if (closed != null) {
                reasons.add(d + " is not a " + scheme.schemeName() + " business day (" + closed + ")");
                continue;
            }
            Instant cutoff = cutoff(scheme, d);
            if (!now.isBefore(cutoff)) {
                reasons.add(scheme.schemeName() + " cut-off " + HH_MM.format(scheme.cutoffTime()) + " " + zone.getId()
                        + " on " + d + " has passed");
                continue;
            }
            return new ValueDating(scheme.currency(), scheme.schemeName(), earliest, d, cutoff, reasons);
        }
        throw new IllegalStateException("No value date within a month of " + earliest + " for " + scheme.currency());
    }

    public static Instant cutoff(SchemeView scheme, LocalDate date) {
        return date.atTime(scheme.cutoffTime()).atZone(ZoneId.of(scheme.timeZone())).toInstant();
    }
}
