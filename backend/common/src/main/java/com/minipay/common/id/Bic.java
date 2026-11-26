package com.minipay.common.id;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Business Identifier Code (ISO 9362), e.g. {@code MNPAMYKLXXX}.
 *
 * <pre>
 *   MNPA  MY  KL  XXX
 *   │     │   │   └─ branch code (optional; XXX = head office)
 *   │     │   └───── location code (2 alphanumerics)
 *   │     └───────── ISO 3166 country code
 *   └─────────────── business party prefix (4 alphanumerics since the 2018 standard)
 * </pre>
 *
 * <p>This type checks <em>format</em> only. Whether a BIC actually exists (and on which date) is a
 * reference-data question answered by the effective-dated BIC directory (ADR-004).
 * An 8-character BIC is normalised to the 11-character head-office form ({@code ...XXX}) so that
 * equality works as banks expect.
 */
public record Bic(String value) {

    private static final Pattern BIC = Pattern.compile("^[A-Z0-9]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$");

    public Bic {
        Objects.requireNonNull(value, "value");
        if (!BIC.matcher(value).matches()) {
            throw new IllegalArgumentException("Not a valid BIC format: " + value);
        }
        if (value.length() == 8) {
            value = value + "XXX";
        }
    }

    public static Bic parse(String text) {
        Objects.requireNonNull(text, "text");
        return new Bic(text.trim().toUpperCase(Locale.ROOT));
    }

    public static boolean isValid(String text) {
        return text != null && BIC.matcher(text).matches();
    }

    public String countryCode() {
        return value.substring(4, 6);
    }

    /** First 8 characters: identifies the institution regardless of branch. */
    public String bic8() {
        return value.substring(0, 8);
    }

    @Override
    public String toString() {
        return value;
    }
}
