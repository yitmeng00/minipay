package com.minipay.ledger.api;

/**
 * Every way a ledger request can be refused.
 *
 * <p>Where one exists, we use the real ISO 20022 External Status Reason code — the same code a bank
 * would put in a pacs.002 rejection — so the payment hub (M2) can pass it straight through.
 */
public enum LedgerError {
    /** AC01 IncorrectAccountNumber. */
    ACCOUNT_NOT_FOUND("AC01", Category.NOT_FOUND),
    /** AC04 ClosedAccountNumber. */
    ACCOUNT_CLOSED("AC04", Category.REJECTED),
    /** AC06 BlockedAccount. */
    ACCOUNT_BLOCKED("AC06", Category.REJECTED),
    /** AM04 InsufficientFunds. */
    INSUFFICIENT_FUNDS("AM04", Category.REJECTED),
    /** AM03 NotAllowedCurrency — currency differs from the account's currency. */
    CURRENCY_NOT_ALLOWED("AM03", Category.REJECTED),
    /** AM12 InvalidAmount — zero or negative amount. */
    INVALID_AMOUNT("AM12", Category.REJECTED),
    UNBALANCED_ENTRY(null, Category.REJECTED),
    INVALID_ENTRY(null, Category.REJECTED),
    RESERVATION_NOT_FOUND(null, Category.NOT_FOUND),
    RESERVATION_NOT_ACTIVE(null, Category.CONFLICT),
    ENTRY_NOT_FOUND(null, Category.NOT_FOUND),
    ENTRY_NOT_REVERSIBLE(null, Category.REJECTED),
    ENTRY_ALREADY_REVERSED(null, Category.CONFLICT),
    DUPLICATE_ACCOUNT(null, Category.CONFLICT),
    /** Same Idempotency-Key, different request — the client has a bug. HTTP 422. */
    IDEMPOTENCY_KEY_REUSED(null, Category.REJECTED);

    public enum Category { NOT_FOUND, REJECTED, CONFLICT }

    private final String isoReasonCode;
    private final Category category;

    LedgerError(String isoReasonCode, Category category) {
        this.isoReasonCode = isoReasonCode;
        this.category = category;
    }

    /** ISO 20022 ExternalStatusReason1Code, or null if there is no ISO equivalent. */
    public String isoReasonCode() {
        return isoReasonCode;
    }

    public Category category() {
        return category;
    }
}
