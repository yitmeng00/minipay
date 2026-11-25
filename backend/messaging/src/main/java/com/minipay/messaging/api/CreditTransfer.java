package com.minipay.messaging.api;

import com.minipay.common.iso.ChargeBearer;
import com.minipay.common.money.Money;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Everything a pacs.008 carries for one customer credit transfer.
 *
 * @param instructingAgentBic who sends this message (InstgAgt; us for an outgoing payment)
 * @param instructedAgentBic  who receives it (InstdAgt): the next bank on the route
 * @param interbankAmount     IntrBkSttlmAmt: what moves between the banks
 * @param instructedAmount    InstdAmt: what the debtor instructed
 * @param ourCharges          charges taken by the instructing agent (ChrgsInf), zero if none
 */
public record CreditTransfer(
        String uetr,
        String instructionId,
        String endToEndId,
        String instructingAgentBic,
        String instructedAgentBic,
        String debtorAgentBic,
        String creditorAgentBic,
        String debtorName,
        String debtorAccount,
        String creditorName,
        String creditorAccount,
        Money interbankAmount,
        Money instructedAmount,
        Money ourCharges,
        ChargeBearer chargeBearer,
        LocalDate settlementDate,
        SettlementMethod settlementMethod,
        String remittanceInformation) {

    public CreditTransfer {
        Objects.requireNonNull(uetr, "uetr");
        Objects.requireNonNull(instructingAgentBic, "instructingAgentBic");
        Objects.requireNonNull(instructedAgentBic, "instructedAgentBic");
        Objects.requireNonNull(interbankAmount, "interbankAmount");
        Objects.requireNonNull(chargeBearer, "chargeBearer");
        Objects.requireNonNull(settlementDate, "settlementDate");
        Objects.requireNonNull(settlementMethod, "settlementMethod");
    }
}
