package com.minipay.compliance.api;

import java.time.LocalDate;

public record SanctionsListView(String code, String name, String authority, LocalDate snapshotDate, int activeEntries) {
}
