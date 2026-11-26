package com.minipay.recon.api;

import com.minipay.common.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** What reconciliation shows and takes. Amounts are signed from our (the owner's) point of view: + money in. */
public final class ReconViews {

    private ReconViews() {
    }

    public record Receipt(UUID statementId, String account, LocalDate date, int entries, int matched, int openBreaks, boolean replayed) {
    }

    public record Published(LocalDate date, List<String> accounts, boolean replayed) {
    }

    /** @param lastDate the latest statement date, or null if none has arrived yet */
    public record Nostro(String account, String name, String currency, String servicerBic, String servicerAccount,
                         LocalDate lastDate, int openBreaks, Money ledgerBalance) {
    }

    public record StatementInfo(UUID id, UUID messageId, String msgId, String statementRef, String servicerBic,
                                String servicerAccount, LocalDate date, Instant receivedAt, Money opening, Money closing,
                                int entries) {
    }

    /**
     * @param ledgerClosing   our nostro's booked balance at the end of the statement date
     * @param difference      statement closing − ledger closing
     * @param openingDiff     what the balances differed by before the first statement (should be zero)
     * @param explained       openingDiff + every item up to that date not matched within it (open breaks, timing)
     * @param unexplained     difference − explained: zero when the books and the statements tell one story
     */
    public record Proof(Money statementClosing, Money ledgerClosing, Money difference, Money openingDiff, Money explained,
                        Money unexplained) {
    }

    public record LedgerLine(long postingId, UUID journalEntryId, LocalDate date, Instant bookedAt, Money signed,
                             String uetr, String endToEndId, String narrative) {
    }

    public record StatementLineView(long id, String entryRef, LocalDate date, Instant bookedAt, Money signed, String servicerRef,
                                    String uetr, String endToEndId, String info, String bankTxCode, boolean reversal,
                                    int transactions, String xml) {
    }

    /**
     * A row of the matching table: a match (rule, by whom) or a break (its id, type, status), with the items on each
     * side.
     */
    public record Row(Long matchId, String rule, String matchedBy, String comment, String breakId, String breakType,
                      String breakStatus, List<LedgerLine> ledger, List<StatementLineView> statement) {
    }

    /**
     * @param action      CARRY_FORWARD, ADJUSTMENT or CLAIM_RAISED when resolved
     * @param suggestion  what to do, for the operator (rule-based)
     */
    public record BreakView(String id, String account, String type, String status, Money difference, Instant openedAt,
                            String reference, LedgerLine ledger, StatementLineView statement, String suggestion,
                            List<String> actions, String action, String comment, String resolvedBy, Instant resolvedAt) {
    }

    public record Reconciliation(String account, String name, String currency, String servicerBic, String servicerAccount,
                                 List<LocalDate> dates, StatementInfo statement, int ledgerEntries, int statementEntries,
                                 int matched, int matchedManually, int openBreaks, Money unmatchedValue, Proof proof,
                                 List<Row> rows, List<BreakView> breaks) {
    }

    /** @param action CARRY_FORWARD, ADJUSTMENT (books the difference) or CLAIM_RAISED */
    public record Resolve(String action, String comment, String by) {
    }

    public record ManualMatch(String account, List<Long> ledgerPostingIds, List<Long> statementEntryIds, String comment,
                              String by) {
    }
}
