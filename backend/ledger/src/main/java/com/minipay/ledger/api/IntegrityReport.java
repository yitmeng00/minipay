package com.minipay.ledger.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * End-of-day integrity check — the questions an auditor asks of any ledger.
 *
 * <ol>
 *   <li>Does every journal entry balance per currency and balance type?</li>
 *   <li>Does every stored balance equal the sum of its postings?</li>
 *   <li>Does the trial balance net to zero per currency (total debits = total credits)?</li>
 *   <li>Does each account's reserved balance equal the sum of its active holds?</li>
 * </ol>
 * {@code ok} is true only if all lists of problems are empty and every trial-balance line balances.
 */
public record IntegrityReport(
        Instant checkedAt,
        boolean ok,
        long journalEntries,
        long postings,
        List<UnbalancedEntry> unbalancedEntries,
        List<BalanceMismatch> balanceMismatches,
        List<HoldMismatch> holdMismatches,
        List<TrialBalanceLine> trialBalance) {

    public record UnbalancedEntry(UUID journalEntryId, String currency, BalanceType balanceType, BigDecimal difference) {
    }

    public record BalanceMismatch(long accountId, String accountNumber,
                                  BigDecimal storedBooked, BigDecimal derivedBooked,
                                  BigDecimal storedReserved, BigDecimal derivedReserved) {
    }

    public record HoldMismatch(long accountId, String accountNumber, BigDecimal reserved, BigDecimal activeHolds) {
    }

    public record TrialBalanceLine(String currency, BalanceType balanceType, BigDecimal debits, BigDecimal credits,
                                   boolean balanced) {
    }
}
