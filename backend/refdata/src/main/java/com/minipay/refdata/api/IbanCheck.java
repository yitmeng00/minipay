package com.minipay.refdata.api;

import java.util.List;

/**
 * Result of validating an IBAN against the IBAN registry on a date. Every check is reported, not just
 * the first failure, so an operator can see exactly what is wrong.
 *
 * @param electronic the IBAN without spaces, upper case
 * @param valid      true if the country is supported and the characters, length, BBAN structure and
 *                   check digits are all right. A bank code that is missing from the IBAN-to-BIC map
 *                   is only a warning: the map is never complete.
 * @param bic        the BIC the IBAN's bank code belongs to, if the map knows it
 */
public record IbanCheck(
        String input,
        String electronic,
        String printFormat,
        boolean valid,
        String countryCode,
        List<Part> parts,
        List<Check> checks,
        String bankId,
        String bic) {

    /** A labelled piece of the IBAN, e.g. ("Sort code", BRANCH, "400512"). */
    public record Part(String name, String role, String value) {
    }

    public enum Outcome { PASS, WARN, FAIL }

    public record Check(String name, Outcome outcome, String detail) {
    }

    /** The first failed check, for a one-line rejection reason. */
    public String firstFailure() {
        return checks.stream().filter(c -> c.outcome() == Outcome.FAIL)
                .map(c -> c.name() + ": " + c.detail()).findFirst().orElse(null);
    }
}
