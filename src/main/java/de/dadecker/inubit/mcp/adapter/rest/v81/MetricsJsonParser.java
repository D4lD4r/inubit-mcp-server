package de.dadecker.inubit.mcp.adapter.rest.v81;

import de.dadecker.inubit.mcp.domain.model.LoadFigures;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses the 8.1 {@code GET /ibis/rest/metrics?format=json} answer (research S-5) into
 * {@link LoadFigures}: {@code usedMemoryInMByte}, {@code freeMemoryInMByte},
 * {@code maxMemoryInMByte}, {@code threadsInUse}, {@code licensedThreads},
 * {@code maxThreadSize} (→ {@code maxThreads}), {@code blockingQueueEntries},
 * {@code maxBlockingQueueSize}; the percentages are derived.
 */
final class MetricsJsonParser {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private MetricsJsonParser() {
    }

    /** The figures, or empty if the body is not JSON or lacks one of the fields. */
    static Optional<LoadFigures> parse(byte[] body) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (JacksonException e) {
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        String[] fields = {"usedMemoryInMByte", "freeMemoryInMByte", "maxMemoryInMByte",
            "threadsInUse", "licensedThreads", "maxThreadSize", "blockingQueueEntries",
            "maxBlockingQueueSize"};
        for (String field : fields) {
            JsonNode value = root.get(field);
            if (value == null || !value.isNumber()) {
                return Optional.empty();
            }
        }
        return Optional.of(LoadFigures.of(
            root.get("usedMemoryInMByte").asDouble(),
            root.get("freeMemoryInMByte").asDouble(),
            root.get("maxMemoryInMByte").asDouble(),
            root.get("threadsInUse").asLong(),
            root.get("licensedThreads").asLong(),
            root.get("maxThreadSize").asLong(),
            root.get("blockingQueueEntries").asLong(),
            root.get("maxBlockingQueueSize").asLong()));
    }
}
