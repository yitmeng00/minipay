package com.minipay.refdata.api;

public class RefDataException extends RuntimeException {

    private final RefDataError error;

    public RefDataException(RefDataError error, String message) {
        super(message);
        this.error = error;
    }

    public RefDataError error() {
        return error;
    }
}
