package com.minipay.compliance.internal.engine;

import java.time.LocalDate;
import java.util.List;

/** One version of a sanctions list entry, as the screener sees it. @param validTo exclusive; null = still listed */
public record ListEntry(long pk, String entryId, String listCode, String listName, String type, String name,
                        List<String> aliases, String country, String address, String registration, LocalDate dateOfBirth,
                        String bic, String program, LocalDate validFrom, LocalDate validTo) {
}
