package com.minipay.compliance.api;

/** The payment's party next to the list entry, attribute by attribute. @param match Yes, Strong, Partial, Weak, No or n/a */
public record ComparisonRow(String attribute, String party, String listed, String match) {
}
