package com.minipay.refdata.api;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Where a currency's settlement day stands right now.
 *
 * @param settlementDate the date a payment sent now would settle on
 * @param cutoff         the cut-off for that date, as an instant (display it in any time zone)
 * @param openToday      false if today, in the scheme's time zone, is a weekend or holiday
 */
public record CutoffView(
        String currency,
        String schemeName,
        String timeZone,
        LocalTime cutoffTime,
        LocalDate settlementDate,
        Instant cutoff,
        boolean openToday,
        String holidayToday) {
}
