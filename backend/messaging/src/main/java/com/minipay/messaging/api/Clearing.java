package com.minipay.messaging.api;

/**
 * A pacs.008 that goes through a clearing system (ADR-008): which system, and how.
 *
 * @param system      SttlmInf/ClrSys/Prtry, e.g. {@code T2}, {@code MPNET}, {@code MPINST}
 * @param priority    PmtTpInf/InstrPrty: {@code HIGH} (urgent) or {@code NORM}; null if not stated
 * @param instant     PmtTpInf/LclInstrm/Cd {@code INST}: an instant payment, settled within the system's
 *                    timeout or rejected
 * @param operatorBic when sending: the system's operator, to whom the message is addressed (AppHdr/To)
 *                    while the creditor's bank stays InstdAgt. Null when read from a message (the AppHdr
 *                    says who sent and who received it)
 */
public record Clearing(String system, String priority, boolean instant, String operatorBic) {
}
