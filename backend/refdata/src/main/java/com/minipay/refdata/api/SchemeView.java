package com.minipay.refdata.api;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * The settlement system a currency settles in, with its calendar and our cut-off for customer payments.
 *
 * @param timeZone   the scheme's own time zone; {@code cutoffTime} is wall-clock time there
 * @param validTo    inclusive, or null if open-ended
 */
public record SchemeView(
        String currency,
        String schemeName,
        String calendarCode,
        String timeZone,
        LocalTime cutoffTime,
        LocalDate validFrom,
        LocalDate validTo,
        String releaseVersion) {
}
