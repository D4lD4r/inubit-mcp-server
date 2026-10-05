package de.dadecker.inubit.mcp.domain.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * System information of an INUBIT server (data-model.md → SystemInfo, FR-007).
 *
 * @param maxHeap the maximum heap with unit, e.g. {@code 9216 MB}
 * @param raw     the remaining name/value pairs in document order, bounded by
 *                {@link #MAX_RAW_ENTRIES} entries of at most {@link #MAX_RAW_VALUE_CHARS} chars
 */
public record SystemInfo(
    Optional<String> version,
    Optional<String> jdk,
    Optional<String> os,
    Optional<String> maxHeap,
    Optional<Boolean> tracingEnabled,
    Optional<Integer> schedulerThreads,
    Map<String, String> raw) {

    public static final int MAX_RAW_ENTRIES = 50;
    public static final int MAX_RAW_VALUE_CHARS = 200;

    public SystemInfo {
        version = orEmpty(version);
        jdk = orEmpty(jdk);
        os = orEmpty(os);
        maxHeap = orEmpty(maxHeap);
        tracingEnabled = orEmpty(tracingEnabled);
        schedulerThreads = orEmpty(schedulerThreads);
        Objects.requireNonNull(raw, "raw");
        if (raw.size() > MAX_RAW_ENTRIES) {
            throw new IllegalArgumentException("raw holds more than " + MAX_RAW_ENTRIES
                + " entries");
        }
        raw = Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    }

    private static <T> Optional<T> orEmpty(Optional<T> value) {
        return value == null ? Optional.empty() : value;
    }
}
