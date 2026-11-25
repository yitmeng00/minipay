package com.minipay.refdata.internal.domain;

import java.util.List;
import java.util.regex.Pattern;

/**
 * One country's entry in the IBAN registry: total length and the structure of the BBAN.
 *
 * @param parts the BBAN pieces in order; their lengths add up to {@code length − 4}
 */
public record IbanFormat(String countryCode, int length, List<BbanPart> parts) {

    public IbanFormat {
        int bban = parts.stream().mapToInt(BbanPart::length).sum();
        if (bban != length - 4) {
            throw new IllegalArgumentException(countryCode + ": BBAN parts add up to " + bban + ", expected " + (length - 4));
        }
        parts = List.copyOf(parts);
    }

    /**
     * One piece of the BBAN in the registry's notation: {@code 8!n} = exactly 8 digits, {@code 4!a} =
     * 4 upper-case letters, {@code 11!c} = 11 letters or digits.
     *
     * @param role BANK, BRANCH, ACCOUNT or NATIONAL_CHECK
     */
    public record BbanPart(String name, String role, int length, String charset) {

        private static final Pattern DIGITS = Pattern.compile("[0-9]*");
        private static final Pattern LETTERS = Pattern.compile("[A-Z]*");
        private static final Pattern ALNUM = Pattern.compile("[A-Z0-9]*");

        public boolean accepts(String value) {
            return value.length() == length && switch (charset) {
                case "n" -> DIGITS.matcher(value).matches();
                case "a" -> LETTERS.matcher(value).matches();
                default -> ALNUM.matcher(value).matches();
            };
        }

        public String notation() {
            return length + "!" + charset;
        }

        public String describe() {
            return length + " " + switch (charset) {
                case "n" -> "digits";
                case "a" -> "letters";
                default -> "letters or digits";
            };
        }
    }
}
