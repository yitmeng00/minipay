package com.minipay.compliance.api;

/** A word of a name as written, and whether it matched a word of the other name. */
public record NameToken(String text, boolean matched) {
}
