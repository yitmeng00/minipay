package com.minipay.messaging.api;

/** How well one piece of information survives a translation between MX and MT. */
public enum Fidelity {
    /** Same information, same meaning. */
    EQUIVALENT,
    /** Same information in another form (date/amount format, code list, character set). */
    TRANSFORMED,
    /** Cut to fit the target field (e.g. 4 × 35 characters). */
    TRUNCATED,
    /** No field of its own: implied by the message type or by other fields. */
    IMPLICIT,
    /** The target format has no place for it: it is lost. */
    LOST
}
