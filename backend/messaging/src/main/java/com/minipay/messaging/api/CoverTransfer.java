package com.minipay.messaging.api;

import com.minipay.common.money.Money;
import java.time.LocalDate;

/**
 * A pacs.009 COV: the money leg of a payment sent by the cover method.
 *
 * <p>The debtor's bank sends the customer payment (pacs.008) straight to the creditor's bank, and asks
 * its correspondent (the instructed agent here) to move the money: debit the debtor's bank's account,
 * credit the creditor's bank's account. The underlying customer transfer is copied in, so the
 * correspondent can screen and trace it (the transparency rule behind CBPR+ cover payments).
 *
 * @param debtorBic   the bank whose account is debited (Dbtr)
 * @param creditorBic the bank whose account is credited (Cdtr)
 */
public record CoverTransfer(
        String uetr,
        String instructionId,
        String endToEndId,
        String instructingAgentBic,
        String instructedAgentBic,
        String debtorBic,
        String creditorBic,
        Money amount,
        LocalDate settlementDate,
        CreditTransfer underlying) {
}
