package com.minipay.compliance.api;

public class ComplianceException extends RuntimeException {

    private final ComplianceError error;

    public ComplianceException(ComplianceError error, String message) {
        super(message);
        this.error = error;
    }

    public ComplianceError error() {
        return error;
    }
}
