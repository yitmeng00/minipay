package com.minipay.compliance.internal.engine;

/**
 * Jaro–Winkler similarity of two words, 0 (nothing alike) to 1 (equal). The classic measure for
 * names: it tolerates transpositions and typos ("IVANOVA" ~ "IVANOVAA") and rewards a common prefix,
 * because names are more often misspelt at the end than at the start.
 */
public final class JaroWinkler {

    private static final double PREFIX_SCALE = 0.1;
    private static final int MAX_PREFIX = 4;

    private JaroWinkler() {
    }

    public static double similarity(String a, String b) {
        if (a.equals(b)) {
            return 1.0;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        double jaro = jaro(a, b);
        int prefix = 0;
        for (int i = 0; i < Math.min(MAX_PREFIX, Math.min(a.length(), b.length())) && a.charAt(i) == b.charAt(i); i++) {
            prefix++;
        }
        return jaro + prefix * PREFIX_SCALE * (1 - jaro);
    }

    static double jaro(String a, String b) {
        int window = Math.max(0, Math.max(a.length(), b.length()) / 2 - 1);
        boolean[] aMatched = new boolean[a.length()];
        boolean[] bMatched = new boolean[b.length()];
        int matches = 0;
        for (int i = 0; i < a.length(); i++) {
            int from = Math.max(0, i - window);
            int to = Math.min(b.length() - 1, i + window);
            for (int j = from; j <= to; j++) {
                if (!bMatched[j] && a.charAt(i) == b.charAt(j)) {
                    aMatched[i] = true;
                    bMatched[j] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) {
            return 0.0;
        }
        int transpositions = 0;
        int k = 0;
        for (int i = 0; i < a.length(); i++) {
            if (aMatched[i]) {
                while (!bMatched[k]) {
                    k++;
                }
                if (a.charAt(i) != b.charAt(k)) {
                    transpositions++;
                }
                k++;
            }
        }
        double m = matches;
        return (m / a.length() + m / b.length() + (m - transpositions / 2.0) / m) / 3.0;
    }
}
