package com.minipay.paymenthub.api;

/** What a payment record stands for. */
public enum PaymentKind {
    /** A customer credit transfer (pacs.008): someone's customer pays someone. */
    CUSTOMER,
    /**
     * The money leg of a cover payment (pacs.009 COV) for which we are the correspondent: we debit the
     * debtor's bank's account and credit the creditor's bank's account.
     */
    COVER
}
