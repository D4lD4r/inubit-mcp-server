package de.dadecker.inubit.mcp.domain.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A text with its secrets replaced by {@link #MASK_TOKEN} and the number of replaced spans
 * (feature 019, research D-6).
 */
public record Masked(String text, int count) {

    /** The same token as {@code infra.SecretScrubber.MASK}. */
    public static final String MASK_TOKEN = "***";

    /**
     * Masks {@code spans} in {@code text}: overlapping and adjacent spans are merged, a span is
     * widened so that it never splits a surrogate pair, and every merged span becomes one
     * {@link #MASK_TOKEN}.
     */
    public static Masked of(String text, List<SecretSpan> spans) {
        if (spans.isEmpty()) {
            return new Masked(text, 0);
        }
        List<int[]> ranges = new ArrayList<>(spans.size());
        for (SecretSpan span : spans) {
            if (span.end() > text.length()) {
                throw new IllegalArgumentException("a secret span ends after the text");
            }
            ranges.add(new int[] {widenStart(text, span.start()), widenEnd(text, span.end())});
        }
        ranges.sort(Comparator.comparingInt(r -> r[0]));
        StringBuilder result = new StringBuilder(text.length());
        int count = 0;
        int written = 0;
        int i = 0;
        while (i < ranges.size()) {
            int start = ranges.get(i)[0];
            int end = ranges.get(i)[1];
            i++;
            while (i < ranges.size() && ranges.get(i)[0] <= end) {
                end = Math.max(end, ranges.get(i)[1]);
                i++;
            }
            result.append(text, written, start).append(MASK_TOKEN);
            written = end;
            count++;
        }
        result.append(text, written, text.length());
        return new Masked(result.toString(), count);
    }

    private static int widenStart(String text, int start) {
        boolean inPair = start > 0 && Character.isLowSurrogate(text.charAt(start))
            && Character.isHighSurrogate(text.charAt(start - 1));
        return inPair ? start - 1 : start;
    }

    private static int widenEnd(String text, int end) {
        boolean inPair = end < text.length() && Character.isLowSurrogate(text.charAt(end))
            && Character.isHighSurrogate(text.charAt(end - 1));
        return inPair ? end + 1 : end;
    }
}
