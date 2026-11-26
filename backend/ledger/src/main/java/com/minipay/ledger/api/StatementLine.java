package com.minipay.ledger.api;

import com.minipay.common.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One booked line on an account statement: what a camt.053 {@code Ntry} carries.
 * Exactly one of {@code debit}/{@code credit} is set. {@code balance} is the running booked balance.
 *
 * @param contraAccounts the account number(s) on the other side of the same journal entry
 */
public record StatementLine(
        long postingId,
        Instant bookedAt,
        UUID journalEntryId,
        EntryType entryType,
        UUID paymentId,
        LocalDate valueDate,
        String narrative,
        String contraAccounts,
        Money debit,
        Money credit,
        Money balance) {
}
