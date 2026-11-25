package com.minipay.messaging.api;

public enum MessagingError {
    NOT_FOUND(Category.NOT_FOUND),
    /** The message could not be built, or an inbound message failed the checks (it is still stored). */
    INVALID_MESSAGE(Category.REJECTED),
    /** Only statements for accounts we keep for someone else (customer, vostro) can be issued. */
    NOT_STATEMENTABLE(Category.REJECTED),
    IDEMPOTENCY_KEY_REUSED(Category.REJECTED);

    public enum Category { NOT_FOUND, REJECTED }

    private final Category category;

    MessagingError(Category category) {
        this.category = category;
    }

    public Category category() {
        return category;
    }
}
