package com.minipay.compliance.api;

/**
 * <pre>
 * OPEN ──RELEASE (score &lt; four-eyes threshold)──────────────→ RELEASED
 *  │  └─RELEASE (score ≥ threshold)→ PENDING_APPROVAL ─APPROVE (someone else)→ RELEASED
 *  │                                  │ ├─DECLINE (someone else) → OPEN
 *  │                                  │ └─WITHDRAW (the releaser) → OPEN
 *  ├──REJECT ──────────────────────────────────────────────────→ REJECTED
 *  └──ESCALATE → ESCALATED (to the MLRO; decided like OPEN)
 * </pre>
 */
public enum CaseStatus {
    OPEN,
    ESCALATED,
    PENDING_APPROVAL,
    RELEASED,
    REJECTED;

    public boolean isClosed() {
        return this == RELEASED || this == REJECTED;
    }
}
