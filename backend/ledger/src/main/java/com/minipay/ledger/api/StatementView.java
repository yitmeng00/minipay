package com.minipay.ledger.api;

import com.minipay.common.money.Money;
import java.time.LocalDate;
import java.util.List;

/**
 * Booked activity on one account for one business date (the date is in the bank's time zone).
 * Only BOOKED postings appear — holds are listed separately, as on a real statement.
 */
public record StatementView(
        AccountView account,
        LocalDate date,
        String timeZone,
        Money openingBooked,
        Money totalDebits,
        int debitCount,
        Money totalCredits,
        int creditCount,
        Money closingBooked,
        List<StatementLine> lines,
        List<ReservationView> activeHolds) {
}
