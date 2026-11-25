package com.minipay.refdata.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.minipay.refdata.api.SchemeView;
import com.minipay.refdata.api.ValueDating;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ValueDaterTest {

    static final SchemeView T2 = new SchemeView("EUR", "T2 RTGS", "TARGET", "Europe/Berlin", LocalTime.of(17, 0),
            LocalDate.of(2024, 1, 1), null, "v1");
    static final BusinessCalendar TARGET = new BusinessCalendar("TARGET", Map.of(
            LocalDate.of(2026, 12, 25), "Christmas Day", LocalDate.of(2026, 12, 26), "Christmas Holiday",
            LocalDate.of(2027, 3, 26), "Good Friday", LocalDate.of(2027, 3, 29), "Easter Monday"));

    static ValueDating at(String earliest, String now) {
        return ValueDater.valueDate(T2, TARGET, LocalDate.parse(earliest), Instant.parse(now));
    }

    @Test
    void before_the_cut_off_a_business_day_settles_the_same_day() {
        ValueDating v = at("2026-11-25", "2026-11-25T08:00:00Z"); // 09:00 Frankfurt
        assertThat(v.valueDate()).isEqualTo(LocalDate.of(2026, 11, 25));
        assertThat(v.rolled()).isFalse();
        assertThat(v.cutoff()).isEqualTo(Instant.parse("2026-11-25T16:00:00Z")); // 17:00 CET = 16:00 UTC
    }

    @Test
    void after_the_cut_off_it_rolls_to_the_next_business_day() {
        ValueDating v = at("2026-11-25", "2026-11-25T16:00:00Z"); // exactly 17:00 Frankfurt: too late
        assertThat(v.valueDate()).isEqualTo(LocalDate.of(2026, 11, 26));
        assertThat(v.reasons()).containsExactly("T2 RTGS cut-off 17:00 Europe/Berlin on 2026-11-25 has passed");
    }

    @Test
    void weekends_and_holidays_are_skipped() {
        // Thursday 24 Dec after cut-off → Fri 25 (Christmas), Sat 26, Sun 27 → Monday 28 Dec.
        ValueDating v = at("2026-12-24", "2026-12-24T17:00:00Z");
        assertThat(v.valueDate()).isEqualTo(LocalDate.of(2026, 12, 28));
        assertThat(v.reasons()).hasSize(4).element(1).asString().contains("Christmas Day");
    }

    @Test
    void the_cut_off_follows_the_schemes_daylight_saving_not_ours() {
        // 17:00 in Frankfurt is 16:00 UTC in winter (CET) and 15:00 UTC in summer (CEST).
        assertThat(ValueDater.cutoff(T2, LocalDate.of(2027, 1, 15))).isEqualTo(Instant.parse("2027-01-15T16:00:00Z"));
        assertThat(ValueDater.cutoff(T2, LocalDate.of(2027, 7, 15))).isEqualTo(Instant.parse("2027-07-15T15:00:00Z"));
    }

    @Test
    void a_future_date_is_not_affected_by_todays_cut_off() {
        ValueDating v = at("2026-11-27", "2026-11-25T20:00:00Z");
        assertThat(v.valueDate()).isEqualTo(LocalDate.of(2026, 11, 27));
    }

    @Test
    void easter_closes_a_long_weekend() {
        assertThat(TARGET.nextBusinessDay(LocalDate.of(2027, 3, 26))).isEqualTo(LocalDate.of(2027, 3, 30));
        assertThat(TARGET.whyClosed(LocalDate.of(2027, 3, 27))).isEqualTo("Saturday");
    }
}
