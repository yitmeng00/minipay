package com.minipay.messaging.api;

import com.minipay.common.money.Money;
import java.time.LocalDate;
import java.util.List;

/**
 * What a message says, read without storing it: for an observer of the network (the UETR tracker)
 * rather than a party to it.
 *
 * @param amount  the interbank amount (pacs.008/009), the returned amount (pacs.004), the credited amount
 *                (camt.054); null for a pacs.002
 * @param charges ChrgsInf: every deduction listed in the message
 * @param creditorAgentBic CdtrAgt of a pacs.008: the bank the payment is finally for, wherever this hop ends
 */
public record MessageSummary(
        MessageType type,
        String msgId,
        String senderBic,
        String receiverBic,
        String uetr,
        String endToEndId,
        String originalMsgId,
        Money amount,
        LocalDate settlementDate,
        String settlementMethod,
        String creditorAgentBic,
        String status,
        String reasonCode,
        List<Charge> charges,
        List<String> errors) {
}
