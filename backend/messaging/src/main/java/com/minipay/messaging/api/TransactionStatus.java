package com.minipay.messaging.api;

/** ISO 20022 ExternalPaymentTransactionStatus1Code values MiniPay uses in pacs.002. */
public enum TransactionStatus {
    /** AcceptedCustomerProfile: the next agent accepted the payment for processing. */
    ACCP,
    /** AcceptedSettlementInProcess. */
    ACSP,
    /** AcceptedSettlementCompleted: the money has arrived. */
    ACSC,
    /** Rejected. */
    RJCT
}
