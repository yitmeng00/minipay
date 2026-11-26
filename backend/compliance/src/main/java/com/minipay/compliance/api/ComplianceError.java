package com.minipay.compliance.api;

public enum ComplianceError {
    NOT_FOUND,
    INVALID_REQUEST,
    /** The decision is not possible in the case's current status (someone else decided first). */
    ILLEGAL_DECISION,
    /** The same person cannot release and approve. */
    FOUR_EYES,
    IDEMPOTENCY_CONFLICT
}
