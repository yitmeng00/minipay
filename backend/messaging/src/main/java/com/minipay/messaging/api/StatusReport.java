package com.minipay.messaging.api;

/**
 * Content of a pacs.002 for one original payment.
 *
 * @param fromBic    who reports (InstgAgt), e.g. the bank we sent the payment to
 * @param toBic      who is told (InstdAgt)
 * @param reasonCode required for RJCT (ISO ExternalStatusReason1Code)
 * @param charges    on an ACSC from the creditor's bank: what it deducted before crediting, so the
 *                   whole route can see the amount the creditor received (as gpi confirmations do)
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
        String additionalInfo,
        java.util.List<Charge> charges) {

    public StatusReport {
        charges = charges == null ? java.util.List.of() : java.util.List.copyOf(charges);
    }

    public StatusReport(String fromBic, String toBic, String originalMsgId, String originalInstructionId,
                        String originalEndToEndId, String originalUetr, TransactionStatus status, String reasonCode,
                        String additionalInfo) {
        this(fromBic, toBic, originalMsgId, originalInstructionId, originalEndToEndId, originalUetr, status, reasonCode,
                additionalInfo, java.util.List.of());
    }
}
