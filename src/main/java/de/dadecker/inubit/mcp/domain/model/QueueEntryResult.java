package de.dadecker.inubit.mcp.domain.model;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The result of {@code read_queue_entry} (feature 020, FR-008…FR-011; shape per
 * contracts/mcp-tools-delta.md "Output"): the entry's metadata, its data stream and its
 * variables, each part classified, scrubbed, masked and bounded, or unavailable with a reason.
 * Empty optionals are left out of the JSON.
 */
public record QueueEntryResult(NodeId node, Entry entry, Data data, Variables variables,
    List<String> warnings) {

    /** The first warning of every result (contracts/mcp-tools-delta.md "Output"). */
    public static final String WARNING =
        "Read through an unsupported, undocumented INUBIT interface; diagnosis only";

    public QueueEntryResult {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(variables, "variables");
        warnings = List.copyOf(warnings);
    }

    /** The Queue Manager row; {@code errorTexts} are the row's, scrubbed. */
    public record Entry(String processId, Optional<String> globalProcessId, String workflow,
        Optional<String> module, Optional<String> moduleType, ProcessState state, String rawState,
        Instant since, String fileName, long sizeBytes, SortedMap<String, String> errorTexts) {

        public Entry {
            Objects.requireNonNull(processId, "processId");
            Objects.requireNonNull(globalProcessId, "globalProcessId");
            Objects.requireNonNull(workflow, "workflow");
            Objects.requireNonNull(module, "module");
            Objects.requireNonNull(moduleType, "moduleType");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(rawState, "rawState");
            Objects.requireNonNull(since, "since");
            Objects.requireNonNull(fileName, "fileName");
            errorTexts = Collections.unmodifiableSortedMap(new TreeMap<>(errorTexts));
        }
    }

    /**
     * The data stream: unavailable with a {@code reason}, or {@code kind} {@code empty},
     * {@code binary} (size, {@code detected} signature if one matched, {@code hint}) or
     * {@code text} (charset, sizes, the masked text cut at {@code maxChars}).
     */
    public record Data(boolean available, Optional<String> reason, Optional<String> kind,
        Optional<String> charset, Optional<Long> sizeBytes, Optional<Integer> totalChars,
        Optional<Integer> returnedChars, Optional<Boolean> truncated, Optional<String> text,
        Optional<BinarySignature> detected, Optional<String> hint) {

        /** The {@code hint} of a binary data stream. */
        public static final String BINARY_HINT = "Binary content is not returned";

        public Data {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(charset, "charset");
            Objects.requireNonNull(sizeBytes, "sizeBytes");
            Objects.requireNonNull(totalChars, "totalChars");
            Objects.requireNonNull(returnedChars, "returnedChars");
            Objects.requireNonNull(truncated, "truncated");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(detected, "detected");
            Objects.requireNonNull(hint, "hint");
        }

        public static Data unavailable(String reason) {
            return new Data(false, Optional.of(reason), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
        }

        public static Data empty() {
            return new Data(true, Optional.empty(), Optional.of(kindName(ContentKind.EMPTY)),
                Optional.empty(), Optional.of(0L), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }

        public static Data binary(long sizeBytes, Optional<BinarySignature> detected) {
            return new Data(true, Optional.empty(), Optional.of(kindName(ContentKind.BINARY)),
                Optional.empty(), Optional.of(sizeBytes), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), detected, Optional.of(BINARY_HINT));
        }

        /**
         * @param totalChars the length of the masked text before the cut
         * @param text       the masked text, cut
         */
        public static Data text(String charset, long sizeBytes, int totalChars, String text) {
            return new Data(true, Optional.empty(), Optional.of(kindName(ContentKind.TEXT)),
                Optional.of(charset), Optional.of(sizeBytes), Optional.of(totalChars),
                Optional.of(text.length()), Optional.of(text.length() < totalChars),
                Optional.of(text), Optional.empty(), Optional.empty());
        }
    }

    /**
     * The variables: unavailable with a {@code reason}, or at most
     * {@value QueueEntryBounds#MAX_VARIABLES} items in INUBIT's order.
     */
    public record Variables(boolean available, Optional<String> reason,
        Optional<Integer> totalCount, Optional<Integer> returnedCount, Optional<Boolean> truncated,
        Optional<List<Variable>> items) {

        public Variables {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(totalCount, "totalCount");
            Objects.requireNonNull(returnedCount, "returnedCount");
            Objects.requireNonNull(truncated, "truncated");
            items = items.map(List::copyOf);
        }

        public static Variables unavailable(String reason) {
            return new Variables(false, Optional.of(reason), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
        }

        /** @param totalCount the number of variables read, {@code items} the first of them */
        public static Variables of(int totalCount, List<Variable> items) {
            return new Variables(true, Optional.empty(), Optional.of(totalCount),
                Optional.of(items.size()), Optional.of(items.size() < totalCount),
                Optional.of(items));
        }
    }

    /**
     * One variable. A credential-named one has the placeholder as {@code value} and
     * {@code masked} true; otherwise {@code kind} {@code empty} or {@code binary} (with
     * {@code sizeBytes} and {@code detected}) carries no value, {@code text} the masked value,
     * cut. {@code nameTruncated}, {@code totalChars} and {@code truncated} are present only for a
     * cut name or value.
     */
    public record Variable(String name, Optional<Boolean> nameTruncated, boolean system,
        Optional<String> type, String kind, Optional<String> value, Optional<Long> sizeBytes,
        Optional<BinarySignature> detected, boolean masked, Optional<Integer> totalChars,
        Optional<Boolean> truncated) {

        public Variable {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(nameTruncated, "nameTruncated");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(sizeBytes, "sizeBytes");
            Objects.requireNonNull(detected, "detected");
            Objects.requireNonNull(totalChars, "totalChars");
            Objects.requireNonNull(truncated, "truncated");
        }
    }

    /** The JSON name of a {@link ContentKind}. */
    public static String kindName(ContentKind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }
}
