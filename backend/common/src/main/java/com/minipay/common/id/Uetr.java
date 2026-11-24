package com.minipay.common.id;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Unique End-to-end Transaction Reference.
 *
 * <p>SWIFT gpi made the UETR mandatory on every cross-border payment: it is created once by the
 * debtor's bank and carried unchanged through every hop (pacs.008 {@code PmtId/UETR}, MT103 field
 * 121) so the payment can be tracked end to end, even through several correspondents.
 *
 * <p>The format is a lower-case RFC 4122 <b>version 4</b> UUID — the variant nibble must be 8, 9,
 * a or b. Networks reject anything else, so we validate the exact pattern used in the ISO 20022
 * schema ({@code UUIDv4Identifier}).
 */
public record Uetr(String value) {

    private static final Pattern UUID_V4 =
            Pattern.compile("^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$");

    public Uetr {
        Objects.requireNonNull(value, "value");
        if (!UUID_V4.matcher(value).matches()) {
            throw new IllegalArgumentException("Not a valid UETR (lower-case UUIDv4): " + value);
        }
    }

    public static Uetr generate() {
        return new Uetr(UUID.randomUUID().toString());
    }

    /** Parses a UETR, accepting upper case input but storing the canonical lower-case form. */
    public static Uetr parse(String text) {
        Objects.requireNonNull(text, "text");
        return new Uetr(text.trim().toLowerCase(java.util.Locale.ROOT));
    }

    public static boolean isValid(String text) {
        return text != null && UUID_V4.matcher(text).matches();
    }

    @Override
    public String toString() {
        return value;
    }
}
