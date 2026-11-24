package com.minipay.paymenthub.api;

public class PaymentException extends RuntimeException {

    private final PaymentError error;

    public PaymentException(PaymentError error, String message) {
        super(message);
        this.error = error;
    }

    public PaymentError error() {
        return error;
    }
}
