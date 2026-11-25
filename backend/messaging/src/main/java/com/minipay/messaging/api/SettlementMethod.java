package com.minipay.messaging.api;

/** pacs.008 GrpHdr/SttlmInf/SttlmMtd: on whose books the interbank settlement happens. */
public enum SettlementMethod {
    /** On the instructed agent's books: it debits the account we hold with it (our nostro). */
    INDA,
    /** On the instructing agent's (our) books: we credit the account it holds with us (its vostro). */
    INGA,
    /** Through a separate cover payment (pacs.009 COV) via correspondents. */
    COVE,
    /** Through a clearing or RTGS system. */
    CLRG
}
