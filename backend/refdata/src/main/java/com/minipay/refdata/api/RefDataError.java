package com.minipay.refdata.api;

/** Every way a reference data request can be refused. */
public enum RefDataError {
    NOT_FOUND(Category.NOT_FOUND),
    /** No settlement scheme (calendar, cut-off) for the currency on that date. */
    NO_SCHEME(Category.REJECTED),
    INVALID_RULE(Category.REJECTED),
    /** Publisher and approver are the same person. */
    FOUR_EYES_VIOLATION(Category.REJECTED),
    /** Another rule with the same destination, currency and priority is valid on overlapping dates. */
    RULE_OVERLAP(Category.CONFLICT),
    /** Same Idempotency-Key, different request. HTTP 422. */
    IDEMPOTENCY_KEY_REUSED(Category.REJECTED);

    public enum Category { NOT_FOUND, REJECTED, CONFLICT }

    private final Category category;

    RefDataError(Category category) {
        this.category = category;
    }

    public Category category() {
        return category;
    }
}
