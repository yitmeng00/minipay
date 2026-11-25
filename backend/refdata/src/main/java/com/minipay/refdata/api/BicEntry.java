package com.minipay.refdata.api;

import java.time.LocalDate;
import java.util.List;

/**
 * One version of a BIC directory record.
 *
 * @param validTo last day this version applies (inclusive), or null if open-ended
 */
public record BicEntry(
        String bic,
        String institutionName,
        String countryCode,
        String city,
        Connectivity connectivity,
        List<String> currencies,
        LocalDate validFrom,
        LocalDate validTo,
        String releaseVersion) {
}
