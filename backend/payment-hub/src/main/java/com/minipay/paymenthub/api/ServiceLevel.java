package com.minipay.paymenthub.api;

/**
 * How fast the customer wants the payment to go, which decides the ways it may take (ADR-008):
 * <ul>
 *   <li>NORM: normal. Any route; through the ACH (DNS) where the routing table prefers it, the cheapest way.</li>
 *   <li>HIGH: urgent (pacs.008 InstrPrty HIGH). Never through the ACH, whose cycles would delay it; through the
 *       RTGS it is settled before normal payments.</li>
 *   <li>INST: instant (LclInstrm INST). Only through the instant scheme: settled within seconds, or rejected.</li>
 * </ul>
 */
public enum ServiceLevel {
    NORM, HIGH, INST
}
