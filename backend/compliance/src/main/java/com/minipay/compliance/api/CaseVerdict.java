package com.minipay.compliance.api;

/**
 * What the payment hub needs to know about a held payment.
 *
 * @param summary   one line for the payment's history, e.g. "Released by n.rahman, approved by j.tan: …"
 * @param decidedBy who closed the case, e.g. {@code n.rahman+j.tan}
 */
public record CaseVerdict(Verdict verdict, String summary, String decidedBy) {

    public enum Verdict {
        /** No case for this payment (it was not held). */
        NONE,
        PENDING,
        RELEASED,
        REJECTED
    }

    public static CaseVerdict none() {
        return new CaseVerdict(Verdict.NONE, null, null);
    }
}
