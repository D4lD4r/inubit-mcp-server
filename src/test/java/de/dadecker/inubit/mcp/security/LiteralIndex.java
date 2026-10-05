package de.dadecker.inubit.mcp.security;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.TreeMap;

/**
 * Prefilter for many regular expressions: each rule contributes the longest literal that every
 * match of it must contain ({@link #requiredLiteral}), and one Aho-Corasick pass over a text
 * ({@link #find}) tells which literals occur in it. Only those rules (and the rules without a
 * literal) are run on the text, which keeps ~2,000 rules over the whole repository fast.
 *
 * <p>The extraction is conservative: when in doubt (top-level alternation, {@code \Q}, comments
 * mode) a rule gets no literal and is always run. A rule with an inline {@code i} flag anywhere
 * gets a {@linkplain #fold folded} literal that is looked up in the folded text.
 */
final class LiteralIndex {

    /** A literal; {@code folded}: compare with the {@linkplain #fold folded} text. */
    record Literal(String text, boolean folded) {
    }

    private final Automaton exact;
    private final Automaton folded;

    LiteralIndex(List<Literal> literals) {
        List<String> exactTexts = new ArrayList<>();
        List<Integer> exactIds = new ArrayList<>();
        List<String> foldedTexts = new ArrayList<>();
        List<Integer> foldedIds = new ArrayList<>();
        for (int id = 0; id < literals.size(); id++) {
            Literal literal = literals.get(id);
            (literal.folded() ? foldedTexts : exactTexts).add(literal.text());
            (literal.folded() ? foldedIds : exactIds).add(id);
        }
        this.exact = new Automaton(exactTexts, exactIds);
        this.folded = new Automaton(foldedTexts, foldedIds);
    }

    /** The ids (positions in the constructor list) of the literals that occur in {@code text}. */
    List<Integer> find(String text) {
        BitSet found = new BitSet();
        exact.search(text, found);
        if (folded.isNotEmpty()) {
            folded.search(fold(text), found);
        }
        return found.stream().boxed().toList();
    }

    /**
     * Simple case folding as Java applies it for {@code (?iu)}: every code point maps to
     * {@code toLowerCase(toUpperCase(cp))}. Two strings that match case-insensitively (ASCII or
     * Unicode) have equal folds.
     */
    static String fold(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(
            cp -> out.appendCodePoint(Character.toLowerCase(Character.toUpperCase(cp))));
        return out.toString();
    }

    // --- extraction ---------------------------------------------------------------------------

    /**
     * The longest literal that every match of the Java regular expression {@code regex} contains,
     * if there is a safe one. Literal characters at the top level form runs; groups, sets,
     * classes, anchors and zero-width assertions end a run, and a quantifier removes the character
     * it applies to.
     */
    static Optional<Literal> requiredLiteral(String regex) {
        if (regex.contains("\\Q")) {
            return Optional.empty();
        }
        boolean caseInsensitive = false;
        String best = "";
        StringBuilder run = new StringBuilder();
        int depth = 0;
        int i = 0;
        int n = regex.length();
        while (i < n) {
            char c = regex.charAt(i);
            if (c == '\\') {
                if (i + 1 >= n) {
                    return Optional.empty();
                }
                char d = regex.charAt(i + 1);
                if (d < 128 && Character.isLetterOrDigit(d)) {
                    if (depth == 0) {
                        best = longer(best, run);
                    }
                    i = skipEscape(regex, i);
                } else {
                    if (depth == 0) {
                        run.append(d);
                    }
                    i += 2;
                }
            } else if (c == '[') {
                if (depth == 0) {
                    best = longer(best, run);
                }
                i = skipSet(regex, i);
                if (i < 0) {
                    return Optional.empty();
                }
            } else if (c == '(') {
                int flagsEnd = inlineFlagsEnd(regex, i);
                if (flagsEnd > 0) {
                    String flags = regex.substring(i + 2, flagsEnd);
                    if (flags.indexOf('x') >= 0) {
                        return Optional.empty();
                    }
                    caseInsensitive |= flags.indexOf('i') >= 0;
                }
                if (depth == 0) {
                    best = longer(best, run);
                }
                depth++;
                i++;
            } else if (c == ')') {
                depth--;
                i++;
            } else if (c == '|' && depth == 0) {
                return Optional.empty();
            } else if (depth > 0) {
                i++;
            } else if (c == '*' || c == '+' || c == '?' || c == '{') {
                dropLastCodePoint(run);
                best = longer(best, run);
                if (c == '{') {
                    int close = regex.indexOf('}', i);
                    if (close < 0) {
                        return Optional.empty();
                    }
                    i = close + 1;
                } else {
                    i++;
                }
            } else if (c == '.' || c == '^' || c == '$') {
                best = longer(best, run);
                i++;
            } else {
                run.append(c);
                i++;
            }
        }
        best = longer(best, run);
        if (best.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(caseInsensitive ? new Literal(fold(best), true)
            : new Literal(best, false));
    }

    /** Ends the current run: returns the longer of {@code best} and the run, clears the run. */
    private static String longer(String best, StringBuilder run) {
        String candidate = run.toString();
        run.setLength(0);
        return candidate.length() > best.length() ? candidate : best;
    }

    private static void dropLastCodePoint(StringBuilder run) {
        if (run.isEmpty()) {
            return;
        }
        int last = run.length() - 1;
        if (Character.isLowSurrogate(run.charAt(last)) && last > 0
                && Character.isHighSurrogate(run.charAt(last - 1))) {
            last--;
        }
        run.setLength(last);
    }

    /** The end of an inline flag group head {@code (?flags)} or {@code (?flags:}, else -1. */
    private static int inlineFlagsEnd(String regex, int open) {
        if (open + 2 >= regex.length() || regex.charAt(open + 1) != '?') {
            return -1;
        }
        int end = open + 2;
        while (end < regex.length()
                && (Character.isLetter(regex.charAt(end)) || regex.charAt(end) == '-')) {
            end++;
        }
        boolean flags = end > open + 2 && end < regex.length()
            && (regex.charAt(end) == ')' || regex.charAt(end) == ':');
        return flags ? end : -1;
    }

    /** Skips an escape with a letter or digit ({@code \d}, {@code \x{..}}, {@code \p{..}}, ...). */
    private static int skipEscape(String regex, int backslash) {
        char d = regex.charAt(backslash + 1);
        int i = backslash + 2;
        switch (d) {
            case 'x', 'N', 'p', 'P' -> {
                if (i < regex.length() && regex.charAt(i) == '{') {
                    int close = regex.indexOf('}', i);
                    return close < 0 ? regex.length() : close + 1;
                }
                return Math.min(regex.length(), i + (d == 'x' ? 2 : d == 'N' ? 0 : 1));
            }
            case 'u' -> {
                return Math.min(regex.length(), i + 4);
            }
            case 'c' -> {
                return Math.min(regex.length(), i + 1);
            }
            case 'k' -> {
                int close = regex.indexOf('>', i);
                return close < 0 ? regex.length() : close + 1;
            }
            default -> {
                if (Character.isDigit(d)) {
                    while (i < regex.length() && Character.isDigit(regex.charAt(i))) {
                        i++;
                    }
                }
                return i;
            }
        }
    }

    /** Skips a Java character set (nested sets included); returns -1 if it is unterminated. */
    private static int skipSet(String regex, int open) {
        int depth = 0;
        int i = open;
        while (i < regex.length()) {
            char c = regex.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '[') {
                depth++;
                i++;
                if (i < regex.length() && regex.charAt(i) == '^') {
                    i++;
                }
                if (i < regex.length() && regex.charAt(i) == ']') {
                    i++; // a leading ] is literal
                }
                continue;
            }
            if (c == ']' && --depth == 0) {
                return i + 1;
            }
            i++;
        }
        return -1;
    }

    // --- Aho-Corasick -------------------------------------------------------------------------

    /** A multi-pattern automaton over UTF-16 chars (the literals are short, the texts long). */
    private static final class Automaton {

        private final char[][] keys;
        private final int[][] targets;
        private final int[] failure;
        private final int[][] outputs;
        private final int[] outputLink;

        Automaton(List<String> texts, List<Integer> ids) {
            List<TreeMap<Character, Integer>> trie = new ArrayList<>();
            List<List<Integer>> ends = new ArrayList<>();
            trie.add(new TreeMap<>());
            ends.add(new ArrayList<>());
            for (int t = 0; t < texts.size(); t++) {
                int state = 0;
                for (char c : texts.get(t).toCharArray()) {
                    Integer next = trie.get(state).get(c);
                    if (next == null) {
                        next = trie.size();
                        trie.get(state).put(c, next);
                        trie.add(new TreeMap<>());
                        ends.add(new ArrayList<>());
                    }
                    state = next;
                }
                ends.get(state).add(ids.get(t));
            }
            int size = trie.size();
            keys = new char[size][];
            targets = new int[size][];
            outputs = new int[size][];
            for (int s = 0; s < size; s++) {
                keys[s] = new char[trie.get(s).size()];
                targets[s] = new int[trie.get(s).size()];
                int k = 0;
                for (Map.Entry<Character, Integer> edge : trie.get(s).entrySet()) {
                    keys[s][k] = edge.getKey();
                    targets[s][k++] = edge.getValue();
                }
                outputs[s] = ends.get(s).stream().mapToInt(Integer::intValue).toArray();
            }
            failure = new int[size];
            outputLink = new int[size];
            Arrays.fill(outputLink, -1);
            Queue<Integer> queue = new ArrayDeque<>();
            for (int child : targets[0]) {
                queue.add(child);
            }
            while (!queue.isEmpty()) {
                int state = queue.remove();
                for (int k = 0; k < keys[state].length; k++) {
                    char c = keys[state][k];
                    int child = targets[state][k];
                    int fallback = failure[state];
                    while (fallback != 0 && next(fallback, c) < 0) {
                        fallback = failure[fallback];
                    }
                    int target = next(fallback, c);
                    failure[child] = target >= 0 && target != child ? target : 0;
                    int f = failure[child];
                    outputLink[child] = outputs[f].length > 0 ? f : outputLink[f];
                    queue.add(child);
                }
            }
        }

        boolean isNotEmpty() {
            return keys[0].length > 0;
        }

        private int next(int state, char c) {
            int k = Arrays.binarySearch(keys[state], c);
            return k >= 0 ? targets[state][k] : -1;
        }

        void search(String text, BitSet found) {
            if (!isNotEmpty()) {
                return;
            }
            int state = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                int next = next(state, c);
                while (next < 0 && state != 0) {
                    state = failure[state];
                    next = next(state, c);
                }
                state = next < 0 ? 0 : next;
                // outputLink is -1 at the end of the chain (the root has no outputs)
                for (int s = state; s > 0; s = outputLink[s]) {
                    for (int id : outputs[s]) {
                        found.set(id);
                    }
                }
            }
        }
    }
}
