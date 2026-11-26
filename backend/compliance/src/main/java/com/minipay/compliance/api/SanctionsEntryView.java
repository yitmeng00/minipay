package com.minipay.compliance.api;

import java.time.LocalDate;
import java.util.List;

/** @param listedTo last day listed (inclusive), null while listed */
public record SanctionsEntryView(String entryId, String listCode, String entryType, String name, List<String> aliases,
                                 String country, String address, String registration, LocalDate dateOfBirth, String bic,
                                 String program, LocalDate listedFrom, LocalDate listedTo) {
}
