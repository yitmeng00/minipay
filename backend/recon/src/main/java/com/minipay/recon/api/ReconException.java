package com.minipay.recon.api;

public class ReconException extends RuntimeException {

    private final ReconError error;

    public ReconException(ReconError error, String message) {
        super(message);
        this.error = error;
    }

    public ReconError error() {
        return error;
    }
}
