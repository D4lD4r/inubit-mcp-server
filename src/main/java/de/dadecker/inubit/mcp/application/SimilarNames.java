package de.dadecker.inubit.mcp.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Suggestions for a name that was not found (Story 3 / AS 5): candidates whose lower-case form
 * contains the lower-case name (or is contained in it, for candidates of at least
 * {@value #MIN_CONTAINED} chars) or lies within an edit distance of
 * {@code max(2, length / 3)}; ordered by Levenshtein distance (case-insensitive), then by name.
 */
final class SimilarNames {

    static final int MIN_CONTAINED = 3;
    /** Longer names are compared by their first chars only (bounded work per candidate). */
    private static final int MAX_COMPARED_CHARS = 400;

    private SimilarNames() {
    }

    static List<String> of(String name, Collection<String> candidates, int max) {
        String query = lower(name);
        int threshold = Math.max(2, query.length() / 3);
        record Scored(String name, int distance) {
        }
        List<Scored> scored = new ArrayList<>();
        for (String candidate : new LinkedHashSet<>(candidates)) {
            if (candidate.equals(name)) {
                continue;
            }
            String lower = lower(candidate);
            int distance = levenshtein(query, lower);
            boolean contains = !query.isEmpty() && (lower.contains(query)
                || (lower.length() >= MIN_CONTAINED && query.contains(lower)));
            if (contains || distance <= threshold) {
                scored.add(new Scored(candidate, distance));
            }
        }
        return scored.stream()
            .sorted(Comparator.comparingInt(Scored::distance).thenComparing(Scored::name))
            .limit(max)
            .map(Scored::name)
            .toList();
    }

    private static String lower(String text) {
        String bounded = text.length() > MAX_COMPARED_CHARS
            ? text.substring(0, MAX_COMPARED_CHARS) : text;
        return bounded.toLowerCase(Locale.ROOT);
    }

    /** The Levenshtein distance of two strings (two-row dynamic programming). */
    static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
