package com.minipay.compliance.api;

/** One input to a score, for the analyst: e.g. ("Name similarity", 93, "0.93"). @param percent null = not known */
public record Factor(String name, Integer percent, String value) {
}
