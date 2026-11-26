package com.minipay.compliance.api;

import java.time.LocalDate;
import java.util.List;

/**
 * One name or BIC of the payment that scored at or above the hold threshold against one list entry.
 *
 * @param matchedOn  NAME, ALIAS or BIC
 * @param listedTo   last day the entry is listed (inclusive); null while listed
 */
public record HitView(
        long id,
        PartyRole role,
        String field,
        String partyName,
        String entryId,
        String listCode,
        String listName,
        String entryType,
        String listedName,
        String matchedOn,
        String matchedName,
        int score,
        List<NameToken> partyTokens,
        List<NameToken> listedTokens,
        List<Factor> factors,
        List<ComparisonRow> comparison,
        String hint,
        LocalDate listedFrom,
        LocalDate listedTo,
        String program) {
}
