package com.minipay.messaging.api;

import com.minipay.common.money.Money;
import java.time.LocalDate;
import java.util.List;

/**
 * Content of a pacs.004 returning one original payment.
 *
 * @param returnedAmount RtrdIntrBkSttlmAmt: may be less than the original, because banks deducted charges
 * @param charges        who kept what (ChrgsInf); their sum is the difference
 * @param reasonCode     ISO return reason (AC04 ClosedAccountNumber, AM09 WrongAmount …)
 */
public record PaymentReturn(
        String fromBic,
        String toBic,
        String returnId,
        String originalMsgId,
        String originalInstructionId,
        String originalEndToEndId,
        String originalUetr,
        Money originalInterbankAmount,
        Money returnedAmount,
        LocalDate settlementDate,
        List<Charge> charges,
        String reasonCode,
        String additionalInfo) {

    public PaymentReturn {
        charges = charges == null ? List.of() : List.copyOf(charges);
    }
}
