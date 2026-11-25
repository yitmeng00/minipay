package com.minipay.common.id;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * International Bank Account Number (ISO 13616), e.g. {@code GB86 MNPC 4005 1288 3344 10}.
 *
 * <pre>
 *   GB  86  MNPC4005128833441 0
 *   │   │   └─ BBAN: the national account number, structured per country (bank code, branch, account …)
 *   │   └───── check digits: chosen so that the whole IBAN is ≡ 1 (mod 97)
 *   └───────── ISO 3166 country code
 * </pre>
 *
 * <p>This type holds the <em>country-independent</em> arithmetic only: normalisation, the character
 * set and the mod-97 check. Length and BBAN structure differ per country and change over time. They are
 * effective-dated reference data (the IBAN registry in the {@code refdata} module), not code.
 *
 * <p>Mod-97 (ISO 7064 MOD 97-10): move the first four characters to the end, replace each letter by
 * two digits (A = 10 … Z = 35), and read the result as one big number. A valid IBAN leaves remainder 1.
 * It catches every single-character error and almost every transposition of two adjacent characters.
 */
public final class Iban {

    private static final Pattern SHAPE = Pattern.compile("^[A-Z]{2}[0-9]{2}[A-Z0-9]{1,30}$");

    private Iban() {
    }

    /** Electronic format: no spaces, upper case. ("Print format" groups it in fours for humans.) */
    public static String normalize(String text) {
        Objects.requireNonNull(text, "text");
        return text.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    /** Country letters, two check digits, then up to 30 letters or digits. Says nothing about the country rules. */
    public static boolean isWellFormed(String electronic) {
        return electronic != null && electronic.length() <= 34 && SHAPE.matcher(electronic).matches();
    }

    /** The mod-97 remainder of a well-formed IBAN; 1 means the check digits are right. */
    public static int remainder(String electronic) {
        if (!isWellFormed(electronic)) {
            throw new IllegalArgumentException("Not a well-formed IBAN: " + electronic);
        }
        return mod97(electronic.substring(4) + electronic.substring(0, 4));
    }

    public static boolean hasValidCheckDigits(String electronic) {
        return isWellFormed(electronic) && remainder(electronic) == 1;
    }

    /** The check digits a BBAN must carry in a given country: 98 − mod97(BBAN + country + "00"). */
    public static String expectedCheckDigits(String countryCode, String bban) {
        int check = 98 - mod97(bban + countryCode + "00");
        return check < 10 ? "0" + check : Integer.toString(check);
    }

    /** "GB86MNPC40051288334410" → "GB86 MNPC 4005 1288 3344 10". */
    public static String printFormat(String electronic) {
        return electronic.replaceAll("(.{4})(?!$)", "$1 ");
    }

    /** Piece-wise remainder, so no BigInteger is needed for a number of up to 68 digits. */
    private static int mod97(String s) {
        int r = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            int v = Character.isDigit(ch) ? ch - '0' : ch - 'A' + 10;
            r = v < 10 ? (r * 10 + v) % 97 : (r * 100 + v) % 97;
        }
        return r;
    }
}
