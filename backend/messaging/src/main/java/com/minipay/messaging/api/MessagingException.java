package com.minipay.messaging.api;

import java.util.List;

public class MessagingException extends RuntimeException {

    private final MessagingError error;
    private final List<String> details;

    public MessagingException(MessagingError error, String message) {
        this(error, message, List.of());
    }

    public MessagingException(MessagingError error, String message, List<String> details) {
        super(message);
        this.error = error;
        this.details = List.copyOf(details);
    }

    public MessagingError error() {
        return error;
    }

    public List<String> details() {
        return details;
    }
}
