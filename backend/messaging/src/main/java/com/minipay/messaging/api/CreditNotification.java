package com.minipay.messaging.api;

import com.minipay.common.money.Money;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A camt.054 credit notification: "your account with us was credited". The cover correspondent sends
 * it to the creditor's bank, which uses it to match the pacs.008 it is holding.
 *
 * @param account the account credited (the receiver's account with the sender)
 */
public record CreditNotification(
        String fromBic,
        String toBic,
        String account,
        Money amount,
        LocalDate valueDate,
        Instant bookedAt,
        String uetr,
        String endToEndId,
        String instructionId,
        String servicerReference) {
}
