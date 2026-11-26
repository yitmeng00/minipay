package com.minipay.compliance.internal.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * How alike two names are, 0–1, independent of word order ("IVANOVA, SVETLANA" vs "Svetlana Ivanova").
 *
 * <ol>
 *   <li>Both names become words ({@link Names#tokens}). Words split or joined differently are aligned
 *       first: "NORTHBRIDGE" in one name and "NORTH BRIDGE" in the other become the same word.</li>
 *   <li>For every word of one name, take the most similar word of the other (Jaro–Winkler); below
 *       {@value #RELATED} the words are unrelated and count 0 (two random words still score ~0.5).
 *       Average them, weighted: generic business words ({@link Names#GENERIC}: TRADING, CAPITAL, MARINE …)
 *       weigh {@value #GENERIC_WEIGHT}, so "Pacific Trading" does not match "ORI Trading". That is how name
 *       A is covered by name B. Do the same the other way round.</li>
 *   <li>Combine the two coverages with a harmonic mean. Both must be high: a one-word name ("DELTA")
 *       is fully covered by "PACIFIC DELTA MINING", but covers only a third of it, so it does not score
 *       as a match.</li>
 * </ol>
 */
public final class NameSimilarity {

    /** Two words this similar count as "the same word" for highlighting and token overlap. */
    static final double SAME_WORD = 0.9;
    /** Below this, two words are unrelated. */
    static final double RELATED = 0.7;
    static final double GENERIC_WEIGHT = 0.3;

    public record Result(double score, Set<String> matchedA, Set<String> matchedB, int sharedWords, int words) {
    }

    private NameSimilarity() {
    }

    public static Result of(String nameA, String nameB) {
        List<String> a = new ArrayList<>(Names.tokens(nameA));
        List<String> b = new ArrayList<>(Names.tokens(nameB));
        align(a, b);
        align(b, a);
        if (a.isEmpty() || b.isEmpty()) {
            return new Result(0, Set.of(), Set.of(), 0, Math.max(a.size(), b.size()));
        }
        Set<String> matchedA = new HashSet<>();
        Set<String> matchedB = new HashSet<>();
        double coverA = cover(a, b, matchedA, matchedB);
        double coverB = cover(b, a, matchedB, matchedA);
        double score = coverA + coverB == 0 ? 0 : 2 * coverA * coverB / (coverA + coverB);
        int shared = (int) a.stream().filter(b::contains).distinct().count();
        return new Result(score, matchedA, matchedB, shared, Math.max(a.size(), b.size()));
    }

    private static double cover(List<String> from, List<String> to, Set<String> matchedFrom, Set<String> matchedTo) {
        double sum = 0;
        double weights = 0;
        for (String f : from) {
            double best = 0;
            String bestWord = null;
            for (String t : to) {
                double s = JaroWinkler.similarity(f, t);
                if (s > best) {
                    best = s;
                    bestWord = t;
                }
            }
            if (best >= SAME_WORD) {
                matchedFrom.add(f);
                matchedTo.add(bestWord);
            }
            double w = Names.GENERIC.contains(f) ? GENERIC_WEIGHT : 1.0;
            sum += w * (best >= RELATED ? best : 0);
            weights += w;
        }
        return sum / weights;
    }

    /** Where two adjacent words of {@code a} written together are one word of {@code b}, join them in {@code a}. */
    private static void align(List<String> a, List<String> b) {
        for (int i = 0; i + 1 < a.size(); i++) {
            String joined = a.get(i) + a.get(i + 1);
            if (b.contains(joined)) {
                a.set(i, joined);
                a.remove(i + 1);
            }
        }
    }
}
