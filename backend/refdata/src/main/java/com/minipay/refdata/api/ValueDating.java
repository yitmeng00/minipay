package com.minipay.refdata.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The earliest date a payment in a currency can settle.
 *
 * @param earliest  the date asked for (e.g. the customer's requested execution date)
 * @param valueDate the first business day on or after {@code earliest} whose cut-off has not passed
 * @param cutoff    the cut-off on {@code valueDate}, as an instant
 * @param reasons   why {@code valueDate} is later than {@code earliest}, one entry per skipped day
 */
public record ValueDating(
        String currency,
        String schemeName,
        LocalDate earliest,
        LocalDate valueDate,
        Instant cutoff,
        List<String> reasons) {

    public boolean rolled() {
        return !valueDate.equals(earliest);
    }
}
