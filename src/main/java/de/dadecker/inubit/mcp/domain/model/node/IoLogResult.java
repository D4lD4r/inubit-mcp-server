package de.dadecker.inubit.mcp.domain.model.node;

import de.dadecker.inubit.mcp.domain.model.BinarySignature;
import de.dadecker.inubit.mcp.domain.model.ContentKind;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.QueueEntryBounds;
import de.dadecker.inubit.mcp.domain.model.QueueEntryResult;
import de.dadecker.inubit.mcp.domain.model.QueueEntryResult.Data;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The result of {@code read_io_log} (feature 024, research D-8, D-9; shape per
 * contracts/mcp-tools-delta.md): the server asked, the INUBIT node that answered, the run, its
 * listed entries after the {@code module} filter, the requested message if any, plus hints and
 * warnings.
 *
 * @param node          the server the call addressed
 * @param answeringNode the INUBIT node that answered ({@code inubitNode})
 * @param workflow      the process name of the request
 * @param processId     the process ID of the request
 * @param total         all entries of the run
 * @param matching      the entries left after the {@code module} filter
 * @param entries       the first {@value IoLogBounds#MAX_LISTED} matching entries, server order
 * @param truncated     whether more entries matched than are listed
 * @param message       the message of the requested {@code ref}; absent for a listing
 * @param hints         {@link #EMPTY_LISTING_HINT} and {@link #RAW_MODULE_HINT} where they apply,
 *                      then the message's {@link Message#hint()}
 * @param warnings      {@link #WARNING} first, then the version gate's warnings
 */
public record IoLogResult(NodeId node, String answeringNode, String workflow, String processId,
    int total, int matching, List<IoLogEntry> entries, boolean truncated,
    Optional<Message> message, List<String> hints, List<String> warnings) {

    /** The warning of every result. */
    public static final String WARNING = ClusterNodesResult.WARNING;
    /** The hint of a run without any logged message (US2-4). */
    public static final String EMPTY_LISTING_HINT = "No logged message for this run:"
        + " input/output logging may be off for the process or its modules, the messages may"
        + " already be removed, or the run may have executed on another cluster node";
    /** The hint of listed entries whose module part is not {@code <name>(<id>)} (D-8). */
    public static final String RAW_MODULE_HINT =
        "Some entries have an unexpected form; they are listed with their raw module part";

    public IoLogResult {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(answeringNode, "answeringNode");
        Objects.requireNonNull(workflow, "workflow");
        Objects.requireNonNull(processId, "processId");
        Objects.requireNonNull(message, "message");
        entries = List.copyOf(entries);
        hints = List.copyOf(hints);
        warnings = List.copyOf(warnings);
    }

    /**
     * The message of one listed entry (D-9), shaped by the content rules of
     * {@code read_queue_entry}: {@code kind} {@code text} (with {@code charset}, the masked
     * {@code text} cut at {@code maxChars}, its length {@code textChars} before the cut, and
     * {@code cut}), {@code binary} ({@code detected} if a signature matched), {@code empty} or
     * {@code tooLarge}; {@code sizeBytes} always; {@code hint} for a binary, empty or too large
     * message.
     */
    public record Message(IoLogEntry entry, String kind, long sizeBytes, Optional<String> charset,
        Optional<BinarySignature> detected, Optional<Integer> textChars, Optional<String> text,
        Optional<Boolean> cut, Optional<String> hint) {

        /** The {@code kind} of a message over {@link QueueEntryBounds#MAX_DATA_BYTES}. */
        public static final String TOO_LARGE = "tooLarge";
        /** The hint of an empty message (FR-023, US2-5). */
        public static final String EMPTY_HINT = "The message is empty or no longer available";

        public Message {
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(charset, "charset");
            Objects.requireNonNull(detected, "detected");
            Objects.requireNonNull(textChars, "textChars");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(cut, "cut");
            Objects.requireNonNull(hint, "hint");
        }

        /**
         * The shaped content of {@code entry}'s message ({@code EntryText}), with the content's
         * own hint (binary) or {@link #EMPTY_HINT}.
         */
        public static Message of(IoLogEntry entry, Data data) {
            String kind = data.kind().orElseThrow();
            Optional<String> hint = kind.equals(QueueEntryResult.kindName(ContentKind.EMPTY))
                ? Optional.of(EMPTY_HINT) : data.hint();
            return new Message(entry, kind, data.sizeBytes().orElseThrow(), data.charset(),
                data.detected(), data.totalChars(), data.text(), data.truncated(), hint);
        }

        /** A message of {@code sizeBytes} over the limit; nothing of it is shown. */
        public static Message tooLarge(IoLogEntry entry, long sizeBytes) {
            return new Message(entry, TOO_LARGE, sizeBytes, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("The message has "
                    + sizeBytes + " bytes and is too large to show (limit "
                    + QueueEntryBounds.MAX_DATA_BYTES / (1024 * 1024) + " MiB)"));
        }
    }
}
