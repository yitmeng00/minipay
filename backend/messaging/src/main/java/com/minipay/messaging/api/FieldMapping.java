package com.minipay.messaging.api;

/** One row of an MX ↔ MT translation report. */
public record FieldMapping(String mtTag, String mtValue, String mxPath, String mxValue, Fidelity fidelity, String note) {
}
