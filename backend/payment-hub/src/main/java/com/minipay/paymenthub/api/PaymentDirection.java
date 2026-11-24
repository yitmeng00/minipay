package com.minipay.paymenthub.api;

/** OUTGOING: we are the debtor's bank. INCOMING: we receive it for a creditor or to pass on (M5). */
public enum PaymentDirection {
    OUTGOING,
    INCOMING
}
