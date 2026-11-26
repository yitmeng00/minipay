package com.minipay.compliance.internal.engine;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns a name into comparable words: transliterated to A–Z (Müller → MULLER, Tromsø → TROMSO),
 * upper case, punctuation dropped, and legal forms and articles removed (LTD, GMBH, SDN BHD, THE …),
 * because "Oakridge Ltd" and "Oakridge LLC" are the same name for screening purposes. Generic words
 * that describe a business (TRADING, CAPITAL, MARINE) are kept: they are part of the name, and the
 * analyst must see that they drove a score.
 */
public final class Names {

    static final Set<String> NOISE = Set.of(
            "LTD", "LIMITED", "LLC", "LLP", "LP", "INC", "INCORPORATED", "CORP", "CORPORATION", "CO", "COMPANY",
            "PLC", "AG", "GMBH", "KG", "SA", "SAS", "SARL", "SRL", "SPA", "BV", "NV", "AS", "ASA", "AB", "OY", "OU",
            "SDN", "BHD", "BERHAD", "PTE", "PVT", "FZE", "FZCO", "FZ", "JSC", "PJSC", "OOO", "THE", "AND", "OF", "DE");

    /** Words that describe a business rather than name it: they count, but weigh little. */
    public static final Set<String> GENERIC = Set.of(
            "TRADING", "TRADE", "CAPITAL", "HOLDINGS", "HOLDING", "GROUP", "INTERNATIONAL", "INTL", "GLOBAL",
            "SERVICES", "SERVICE", "SUPPLIES", "SUPPLY", "SHIPPING", "MARINE", "MARITIME", "FREIGHT", "LOGISTICS",
            "INDUSTRIES", "INDUSTRIAL", "ENTERPRISES", "ENTERPRISE", "INVESTMENTS", "INVESTMENT", "MANAGEMENT",
            "CONSULTING", "SOLUTIONS", "TECHNOLOGIES", "TECHNOLOGY", "PARTNERS", "BANK", "FINANCE", "FINANCIAL",
            "GENERAL", "COMMERCIAL", "EXPORT", "EXPORTS", "IMPORT", "IMPORTS", "WORKS");

    private Names() {
    }

    /** One word as written → its comparable form ("Müller," → "MULLER"; "O'Brien" → "OBRIEN"). */
    public static String fold(String word) {
        String s = word.replace("ß", "SS").replace("Ø", "O").replace("ø", "o").replace("Æ", "AE").replace("æ", "ae")
                .replace("Œ", "OE").replace("œ", "oe").replace("Ł", "L").replace("ł", "l").replace("Đ", "D").replace("đ", "d")
                .replace("Þ", "TH").replace("þ", "th").replace("'", "").replace("’", "");
        s = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        return s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").trim();
    }

    /** The words that take part in matching. If a name is nothing but noise ("The Company"), all its words count. */
    public static List<String> tokens(String name) {
        List<String> all = new ArrayList<>();
        for (String part : fold(name == null ? "" : name).split(" ")) {
            if (!part.isEmpty()) {
                all.add(part);
            }
        }
        List<String> kept = all.stream().filter(t -> !NOISE.contains(t)).toList();
        return kept.isEmpty() ? all : kept;
    }

    /** The two-letter country of an IBAN, or null if the account is not an IBAN. */
    public static String ibanCountry(String account) {
        if (account == null) {
            return null;
        }
        String a = account.replace(" ", "").toUpperCase(Locale.ROOT);
        return a.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}") ? a.substring(0, 2) : null;
    }

    /** The country of a BIC (characters 5–6), or null. */
    public static String bicCountry(String bic) {
        return bic != null && bic.length() >= 6 ? bic.substring(4, 6).toUpperCase(Locale.ROOT) : null;
    }
}
