package com.minipay.messaging.api;

import com.minipay.common.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A received camt.053, as the account owner reads it (M8). Balances and entries are from the holder's point of view:
 * a positive balance is a credit balance (money the servicer holds for us), CRDT is money in.
 *
 * @param servicerBic    who keeps the account and sent the statement
 * @param accountNumber  the account's number at the servicer (its vostro for us)
 * @param openingBooked  OPBD, signed (negative = debit balance)
 * @param closingBooked  CLBD, signed
 */
public record BankStatement(String servicerBic, String accountNumber, String currency, String statementId,
                            LocalDate date, Money openingBooked, Money closingBooked, List<Entry> entries) {

    /**
     * @param entryRef    NtryRef
     * @param servicerRef AcctSvcrRef: the servicer's own reference for the booking
     * @param reversal    RvslInd
     * @param info        AddtlNtryInf
     * @param bankTxCode  domain/family/sub-family, e.g. PMNT/ICDT/XBCT
     * @param txs         TxDtls with references (one for a single payment, several for a batch)
     * @param xml         the entry exactly as it is in the message, for the operator's eyes
     */
    public record Entry(String entryRef, Money amount, boolean credit, Instant bookedAt, LocalDate valueDate,
                        String servicerRef, boolean reversal, String info, String bankTxCode, List<StatementTx> txs,
                        String xml) {
    }
}
