package com.minipay.refdata.internal.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Map;

/**
 * A settlement calendar: every day is a business day except Saturdays, Sundays and listed holidays.
 *
 * <p>Saturday + Sunday is the weekend in all the simulator's markets. (It is not universal: some
 * markets close on Friday, and a few Malaysian states close on Friday and Saturday.)
 */
public record BusinessCalendar(String code, Map<LocalDate, String> holidays) {

    public BusinessCalendar {
        holidays = Map.copyOf(holidays);
    }

    public boolean isBusinessDay(LocalDate d) {
        return whyClosed(d) == null;
    }

    /** "Saturday", "Christmas Day" … or null if the day is a business day. */
    public String whyClosed(LocalDate d) {
        DayOfWeek dow = d.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return dow.getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        }
        return holidays.get(d);
    }

    /** Following convention: the day itself if it is a business day, else the next one. */
    public LocalDate nextBusinessDay(LocalDate from) {
        LocalDate d = from;
        for (int i = 0; i < 31; i++, d = d.plusDays(1)) {
            if (isBusinessDay(d)) {
                return d;
            }
        }
        throw new IllegalStateException("Calendar " + code + " has no business day within a month of " + from);
    }
}
