package com.minipay.messaging.api;

import com.minipay.common.iso.ChargeBearer;
import com.minipay.common.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Everything a pacs.008 carries for one customer credit transfer, on one hop of its route.
 *
 * <p>On each hop the instructing and instructed agents change and the interbank amount may shrink
 * (under SHAR and CRED every bank deducts its charge). The UETR, end-to-end id, parties and instructed
 * amount travel unchanged from end to end.
 *
 * @param instructingAgentBic who sends this message (InstgAgt)
 * @param instructedAgentBic  who receives it (InstdAgt): the next bank on the route
 * @param interbankAmount     IntrBkSttlmAmt on this hop
 * @param instructedAmount    InstdAmt: what the debtor instructed
 * @param charges             ChrgsInf: every deduction so far, in route order
 * @param coverAgentBic       for SttlmMtd COVE, the bank through which the money travels separately
 *                            (InstgRmbrsmntAgt); null otherwise
 * @param previousAgents      PrvsInstgAgt1..3: banks before the instructing agent, oldest first
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
        List<Charge> charges,
        ChargeBearer chargeBearer,
        LocalDate settlementDate,
        SettlementMethod settlementMethod,
        String coverAgentBic,
        List<String> previousAgents,
        String remittanceInformation) {

    public CreditTransfer {
        Objects.requireNonNull(uetr, "uetr");
        Objects.requireNonNull(instructingAgentBic, "instructingAgentBic");
        Objects.requireNonNull(instructedAgentBic, "instructedAgentBic");
        Objects.requireNonNull(interbankAmount, "interbankAmount");
        Objects.requireNonNull(chargeBearer, "chargeBearer");
        Objects.requireNonNull(settlementDate, "settlementDate");
        Objects.requireNonNull(settlementMethod, "settlementMethod");
        charges = charges == null ? List.of() : List.copyOf(charges);
        previousAgents = previousAgents == null ? List.of() : List.copyOf(previousAgents);
    }
}
