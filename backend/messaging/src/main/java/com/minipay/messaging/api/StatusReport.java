package com.minipay.messaging.api;

/**
 * Content of a pacs.002 for one original payment.
 *
 * @param fromBic    who reports (InstgAgt), e.g. the bank we sent the payment to
 * @param toBic      who is told (InstdAgt)
 * @param reasonCode required for RJCT (ISO ExternalStatusReason1Code)
 */
public record StatusReport(
        String fromBic,
        String toBic,
        String originalMsgId,
        String originalInstructionId,
        String originalEndToEndId,
        String originalUetr,
        TransactionStatus status,
        String reasonCode,
        String additionalInfo) {
}
