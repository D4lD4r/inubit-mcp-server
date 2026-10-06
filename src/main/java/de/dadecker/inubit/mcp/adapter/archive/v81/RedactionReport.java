package de.dadecker.inubit.mcp.adapter.archive.v81;

import de.dadecker.inubit.mcp.adapter.archive.v81.SecretRedactor.Kind;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * What a redaction replaced (data-model.md → Secrets): counts per kind and the number of
 * properties kept although their names look secret ({@code suspicious}); never names or values.
 */
public record RedactionReport(Map<Kind, Integer> counts, int suspicious) {

    public RedactionReport {
        Map<Kind, Integer> copy = new EnumMap<>(Kind.class);
        copy.putAll(counts);
        counts = Collections.unmodifiableMap(copy);
        if (suspicious < 0 || copy.values().stream().anyMatch(n -> n < 0)) {
            throw new IllegalArgumentException("counts must not be negative");
        }
    }

    /** The number of replaced values. */
    public int total() {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }
}
