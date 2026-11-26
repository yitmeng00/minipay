package com.minipay.compliance.internal.engine;

import com.minipay.compliance.api.ComparisonRow;
import com.minipay.compliance.api.Factor;
import com.minipay.compliance.api.NameToken;
import com.minipay.compliance.api.ScreenedParty;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Screens the names and BICs of a payment against list entries. Pure: no I/O, no clock.
 *
 * <p><b>Score</b> (0–100), per party and entry:
 * <ul>
 *   <li>a party's BIC equal to a listed bank's BIC (first 8 characters: the institution) scores 100;</li>
 *   <li>otherwise the best {@link NameSimilarity} of the party's name with the entry's name or any of
 *       its aliases, × 100;</li>
 *   <li>+5 when the party's country (from its IBAN, or its bank's BIC) is the entry's country: a
 *       corroborating identifier. A different country never lowers the score, because a sanctioned
 *       party can bank anywhere; it is shown to the analyst instead.</li>
 * </ul>
 * A score at or above the hold threshold is a hit and holds the payment.
 */
public final class Screener {

    public record Hit(ScreenedParty party, ListEntry entry, String matchedOn, String matchedName, int score,
                      List<NameToken> partyTokens, List<NameToken> listedTokens, List<Factor> factors,
                      List<ComparisonRow> comparison, String hint) {
    }

    /** @param bestScore the best score of anything screened, hit or not */
    public record Result(int bestScore, List<Hit> hits, int screenedItems) {
    }

    static final int COUNTRY_BONUS = 5;

    private Screener() {
    }

    public static Result screen(List<ScreenedParty> parties, List<ListEntry> entries, int holdThreshold) {
        int best = 0;
        int items = 0;
        List<Hit> hits = new ArrayList<>();
        for (ScreenedParty party : parties) {
            boolean hasName = party.name() != null && !party.name().isBlank();
            boolean hasBic = party.bic() != null && !party.bic().isBlank();
            if (!hasName && !(party.role().isAgent() && hasBic)) {
                continue;
            }
            items++;
            for (ListEntry entry : entries) {
                Hit hit = match(party, entry);
                if (hit == null) {
                    continue;
                }
                best = Math.max(best, hit.score());
                if (hit.score() >= holdThreshold) {
                    hits.add(hit);
                }
            }
        }
        hits.sort(Comparator.comparingInt(Hit::score).reversed());
        return new Result(best, hits, items);
    }

    static Hit match(ScreenedParty party, ListEntry entry) {
        if (party.role().isAgent()) {
            return bicMatch(party, entry);
        }
        return nameMatch(party, entry);
    }

    private static Hit bicMatch(ScreenedParty party, ListEntry entry) {
        if (entry.bic() == null || party.bic() == null || party.bic().length() < 8
                || !entry.bic().substring(0, 8).equals(party.bic().substring(0, 8).toUpperCase(Locale.ROOT))) {
            return null;
        }
        List<Factor> factors = List.of(new Factor("BIC (institution)", 100, party.bic().substring(0, 8) + " = " + entry.bic().substring(0, 8)));
        List<ComparisonRow> rows = List.of(
                new ComparisonRow("BIC", party.bic(), entry.bic(), "Yes"),
                new ComparisonRow("Country", Names.bicCountry(party.bic()), entry.country(), countryMatch(Names.bicCountry(party.bic()), entry.country())),
                new ComparisonRow("Entity type", "Bank (agent)", label(entry.type()), "Yes"),
                listing(entry));
        return new Hit(party, entry, "BIC", entry.bic(), 100, tokens(party.bic(), Set.of(party.bic().substring(0, 8))),
                tokens(entry.name(), Set.of()), factors, rows,
                "Exact BIC match on a listed bank — the payment involves a sanctioned institution. Reject unless the listing is wrong.");
    }

    private static Hit nameMatch(ScreenedParty party, ListEntry entry) {
        NameSimilarity.Result primary = NameSimilarity.of(party.name(), entry.name());
        NameSimilarity.Result bestAlias = null;
        String bestAliasName = null;
        for (String alias : entry.aliases()) {
            NameSimilarity.Result r = NameSimilarity.of(party.name(), alias);
            if (bestAlias == null || r.score() > bestAlias.score()) {
                bestAlias = r;
                bestAliasName = alias;
            }
        }
        boolean onAlias = bestAlias != null && bestAlias.score() > primary.score();
        NameSimilarity.Result used = onAlias ? bestAlias : primary;
        String partyCountry = partyCountry(party);
        boolean sameCountry = partyCountry != null && partyCountry.equals(entry.country());
        int score = (int) Math.round(used.score() * 100) + (sameCountry ? COUNTRY_BONUS : 0);
        score = Math.min(100, score);
        if (used.score() < 0.5) {
            return new Hit(party, entry, "NAME", entry.name(), score, List.of(), List.of(), List.of(), List.of(), null);
        }

        List<Factor> factors = new ArrayList<>();
        factors.add(new Factor("Name similarity", pct(primary.score()), two(primary.score())));
        factors.add(new Factor("Token overlap", (int) Math.round(100.0 * used.sharedWords() / Math.max(1, used.words())),
                used.sharedWords() + " / " + used.words()));
        factors.add(partyCountry == null || entry.country() == null
                ? new Factor("Country", null, "n/a")
                : new Factor("Country", sameCountry ? 100 : 0, sameCountry ? "same (+" + COUNTRY_BONUS + ")" : partyCountry + " ≠ " + entry.country()));
        factors.add(bestAlias == null ? new Factor("Alias", null, "none listed") : new Factor("Alias", pct(bestAlias.score()), two(bestAlias.score())));

        String aliasMatch = bestAlias == null ? "n/a" : strength(bestAlias.score());
        List<ComparisonRow> rows = new ArrayList<>();
        rows.add(new ComparisonRow("Aliases", "—", entry.aliases().isEmpty() ? "—" : String.join("; ", entry.aliases()), aliasMatch));
        rows.add(new ComparisonRow("Country", partyCountry == null ? "Unknown" : partyCountry + countrySource(party),
                orDash(entry.country()), countryMatch(partyCountry, entry.country())));
        rows.add(new ComparisonRow("Address", "Not in message", orDash(entry.address()), "n/a"));
        if ("INDIVIDUAL".equals(entry.type())) {
            rows.add(new ComparisonRow("Date of birth", "Not in message", entry.dateOfBirth() == null ? "—" : entry.dateOfBirth().toString(), "n/a"));
        } else {
            rows.add(new ComparisonRow("Registration", "Not in message", orDash(entry.registration()), "n/a"));
        }
        rows.add(new ComparisonRow("Entity type", "Not in message", label(entry.type()), "n/a"));
        rows.add(new ComparisonRow("Account / agent", accountAndAgent(party), orDash(entry.bic()), entry.bic() == null ? "n/a" : "No"));
        rows.add(listing(entry));

        String hint = hint(entry, onAlias, bestAlias, primary, sameCountry, partyCountry);
        String matchedName = onAlias ? bestAliasName : entry.name();
        return new Hit(party, entry, onAlias ? "ALIAS" : "NAME", matchedName, score,
                tokens(party.name(), used.matchedA()), tokens(matchedName, used.matchedB()), factors, rows, hint);
    }

    /** A rule-based pointer for the analyst. It never decides: it says what to check. */
    static String hint(ListEntry entry, boolean onAlias, NameSimilarity.Result alias, NameSimilarity.Result primary,
                       boolean sameCountry, String partyCountry) {
        if (alias != null && alias.score() >= 0.99) {
            return "Escalate — an alias matches exactly. Verify the registration or date of birth with the customer before deciding.";
        }
        if ("INDIVIDUAL".equals(entry.type())) {
            return "Request the date of birth before deciding — the message carries none, and names of people are often shared.";
        }
        if (sameCountry) {
            return "Same country as the listed party — compare the registration number and address before releasing.";
        }
        if (partyCountry != null && entry.country() != null && primary.score() < 0.97) {
            return "Likely false positive — name overlap only; the country differs and no identifier corroborates the match.";
        }
        return "Compare the party with the list entry — the message identifies the party by name and account only.";
    }

    static String partyCountry(ScreenedParty party) {
        String c = Names.ibanCountry(party.account());
        return c != null ? c : Names.bicCountry(party.bic());
    }

    private static String countrySource(ScreenedParty party) {
        return Names.ibanCountry(party.account()) != null ? " (IBAN)" : " (bank's BIC)";
    }

    private static String accountAndAgent(ScreenedParty party) {
        String a = party.account() == null ? "—" : party.account();
        return party.bic() == null ? a : a + " · " + party.bic();
    }

    private static ComparisonRow listing(ListEntry entry) {
        return new ComparisonRow("Listing", "—", entry.validFrom() + " · " + entry.program()
                + (entry.validTo() == null ? "" : " · delisted " + entry.validTo()), "n/a");
    }

    private static String countryMatch(String party, String listed) {
        return party == null || listed == null ? "n/a" : party.equals(listed) ? "Yes" : "No";
    }

    static String strength(double s) {
        return s >= 0.95 ? "Strong" : s >= 0.85 ? "Partial" : s >= 0.7 ? "Weak" : "No";
    }

    private static String label(String type) {
        return switch (type) {
            case "INDIVIDUAL" -> "Individual";
            case "BANK" -> "Bank";
            default -> "Entity";
        };
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private static int pct(double s) {
        return (int) Math.round(s * 100);
    }

    private static String two(double s) {
        return String.format(Locale.ROOT, "%.2f", s);
    }

    /** The name as written, word by word, each marked if it took part in the match. */
    static List<NameToken> tokens(String name, Set<String> matched) {
        List<NameToken> out = new ArrayList<>();
        for (String word : name.trim().split("\\s+")) {
            String f = Names.fold(word).replace(" ", "");
            boolean m = !f.isEmpty() && !Names.NOISE.contains(f) && matched.stream()
                    .anyMatch(w -> w.equals(f) || (f.length() >= 3 && w.length() > f.length() && w.contains(f)));
            out.add(new NameToken(word, m));
        }
        return out;
    }
}
