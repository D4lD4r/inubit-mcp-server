package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.ContentKind;
import de.dadecker.inubit.mcp.domain.model.EntryContentClassifier;
import de.dadecker.inubit.mcp.domain.model.EntryContentClassifier.Classified;
import de.dadecker.inubit.mcp.domain.model.EntryPart;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.Names;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.domain.model.QueueEntries;
import de.dadecker.inubit.mcp.domain.model.QueueEntryBounds;
import de.dadecker.inubit.mcp.domain.model.QueueEntryRef;
import de.dadecker.inubit.mcp.domain.model.QueueEntryResult;
import de.dadecker.inubit.mcp.domain.model.QueueEntryResult.Data;
import de.dadecker.inubit.mcp.domain.model.QueueEntryResult.Entry;
import de.dadecker.inubit.mcp.domain.model.QueueEntryResult.Variable;
import de.dadecker.inubit.mcp.domain.model.QueueEntryResult.Variables;
import de.dadecker.inubit.mcp.domain.model.QueueEntryRow;
import de.dadecker.inubit.mcp.domain.model.RawEntryContent;
import de.dadecker.inubit.mcp.domain.model.RawEntryContent.PartResult;
import de.dadecker.inubit.mcp.domain.model.RawVariable;
import de.dadecker.inubit.mcp.domain.model.SecretTextMasker;
import de.dadecker.inubit.mcp.domain.model.SystemVariables;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.domain.port.QueueEntryContentPort;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The use case behind {@code read_queue_entry} (feature 020, FR-001, FR-002, FR-005, FR-012,
 * FR-013, research D-9; texts per contracts/mcp-tools-delta.md "Errors"). One call:
 * <ol>
 *   <li>Inputs that can be checked without INUBIT → {@code INVALID_INPUT}: {@code processId}
 *       ({@code ^[1-9][0-9]{0,18}$}), {@code fileName} (1–255 characters without {@code /},
 *       {@code \}, NUL or other control characters; never echoed), {@code maxChars} (1–100,000,
 *       default 20,000).</li>
 *   <li>{@link TargetResolver#resolveSingleServer}: exactly one node.</li>
 *   <li>The group's opt-in {@code unsupported.queueEntryRead}; without it {@code NOT_CONFIGURED}
 *       before the gate, so nothing is sent (no detection, no lookup, no helper).</li>
 *   <li>{@link VersionGate#admitWithDetection}: an undetectable version fails with the
 *       detection's error, an unsupported one with {@code UNSUPPORTED_VERSION}.</li>
 *   <li>The Queue Manager rows of the process id ({@link ProcessQueryPort#queueEntries}); its
 *       errors (credentials not yet available, {@code MAINTENANCE_MODE}, …) pass through and
 *       nothing is launched. The candidate rules of D-9 pick the row or refuse; a refusal lists
 *       at most {@value QueueEntryBounds#MAX_LISTED_CANDIDATES} rows and says when only part of
 *       the rows was read. The row's error texts are never put into a message.</li>
 *   <li>{@link QueueEntryContentPort#read} with the admitted version; the data stream is not
 *       requested when the row's size exceeds {@link QueueEntryBounds#MAX_DATA_BYTES} and is
 *       then reported as {@link PartResult.TooLarge} with that size.</li>
 *   <li>A part reported {@link PartResult.NotFound}: the rows are read again; the entry gone →
 *       {@code NOT_FOUND} ("left the Queue Manager"), still there → the partial read.</li>
 * </ol>
 * {@link #read} then shapes the {@link RawEntryRead} into the {@link QueueEntryResult}
 * (FR-008…FR-011, D-10…D-12): each text (the data stream, a variable value) is classified on its
 * bytes (a value on its UTF-8 bytes), cleared of the configured credentials ({@code knownSecrets},
 * the {@code SecretScrubber}), masked ({@link SecretTextMasker}) and only then cut, never inside a
 * surrogate pair, so no piece of a secret survives the cut. Of the variables the first
 * {@value QueueEntryBounds#MAX_VARIABLES} are returned; a credential-named one (a term anywhere in
 * its name) only as its placeholder; names are cut at
 * {@value QueueEntryBounds#MAX_NAME_CHARS}, each value at
 * {@value QueueEntryBounds#MAX_VALUE_CHARS} and all values together at
 * {@value QueueEntryBounds#MAX_VALUES_CHARS} characters. Every call logs one INFO line with the
 * node, process ID, file name, sizes and outcome, never content (FR-018, D-16).
 *
 * <p>Thread-safe; it keeps no state of its own.
 */
public final class QueueEntryService {

    /** The tool name, the start of the gate's refusals. */
    static final String TOOL = "read_queue_entry";

    private static final Logger LOG = LoggerFactory.getLogger(QueueEntryService.class);
    private static final Pattern PROCESS_ID = Pattern.compile("^[1-9][0-9]{0,18}$");
    private static final String NOT_FOUND_REASON = "INUBIT did not find the content of this entry";
    private static final String VARIABLES_SCOPE = "variables";
    private static final String UNKNOWN = "-";
    private static final String LOOK_UP_AGAIN = "Look the instance up again with find_processes";

    /**
     * The entry located and read, before classification, masking and bounds.
     *
     * @param node     the resolved node
     * @param row      the selected Queue Manager row (state {@code ERROR})
     * @param content  the parts as read; a data stream not requested for its size is
     *                 {@link PartResult.TooLarge}
     * @param maxChars the text limit of the data stream, validated, default applied
     * @param warnings the gate's warnings for the result
     */
    record RawEntryRead(NodeId node, QueueEntryRow row, RawEntryContent content, int maxChars,
        List<String> warnings) {

        RawEntryRead {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(row, "row");
            Objects.requireNonNull(content, "content");
            warnings = List.copyOf(warnings);
        }
    }

    private final TargetResolver targets;
    private final Predicate<NodeId> optedIn;
    private final VersionGate gate;
    private final Function<NodeId, ProcessQueryPort> processes;
    private final Function<NodeId, QueueEntryContentPort> contents;
    private final UnaryOperator<String> knownSecrets;

    /**
     * @param optedIn      whether the node's group sets {@code unsupported.queueEntryRead: true}
     * @param processes    the process query port of a node (the queueLog lookup)
     * @param contents     the queue-entry content port of a node
     * @param knownSecrets replaces the configured credentials in a text (the
     *                     {@code SecretScrubber}); applied before masking and cutting
     */
    public QueueEntryService(TargetResolver targets, Predicate<NodeId> optedIn, VersionGate gate,
        Function<NodeId, ProcessQueryPort> processes,
        Function<NodeId, QueueEntryContentPort> contents, UnaryOperator<String> knownSecrets) {
        this.targets = Objects.requireNonNull(targets, "targets");
        this.optedIn = Objects.requireNonNull(optedIn, "optedIn");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.processes = Objects.requireNonNull(processes, "processes");
        this.contents = Objects.requireNonNull(contents, "contents");
        this.knownSecrets = Objects.requireNonNull(knownSecrets, "knownSecrets");
    }

    /**
     * Reads the entry and shapes it into the tool's result; logs one INFO line either way.
     *
     * @throws ToolErrorException as described in the class description
     */
    public QueueEntryResult read(String target, String processId, Optional<String> fileName,
        OptionalInt maxChars) {
        RawEntryRead raw = null;
        AtomicReference<QueueEntryRef> selected = new AtomicReference<>();
        Optional<NodeId> failedOn = Optional.empty();
        String outcome = "INTERNAL";
        try {
            raw = readRaw(target, processId, fileName, maxChars, selected::set);
            QueueEntryResult result = result(raw);
            outcome = raw.content().data() instanceof PartResult.Read
                && raw.content().variables() instanceof PartResult.Read ? "OK" : "PARTIAL";
            return result;
        } catch (ToolErrorException e) {
            failedOn = e.error().node();
            outcome = e.error().code().name();
            throw e;
        } finally {
            log(raw, Optional.ofNullable(selected.get()), failedOn, processId, fileName,
                outcome);
        }
    }

    /** The entry read, classified, scrubbed, masked and bounded (FR-008…FR-011). */
    QueueEntryResult result(RawEntryRead raw) {
        List<String> warnings = new ArrayList<>();
        warnings.add(QueueEntryResult.WARNING);
        warnings.addAll(raw.warnings());
        return new QueueEntryResult(raw.node(), entry(raw.row()),
            data(raw.content().data(), raw.maxChars()), variables(raw.content().variables()),
            warnings);
    }

    private Entry entry(QueueEntryRow row) {
        TreeMap<String, String> errorTexts = new TreeMap<>();
        row.errorTexts().forEach((key, text) -> errorTexts.put(key, knownSecrets.apply(text)));
        return new Entry(row.processId(), row.globalProcessId(), row.workflow(), row.module(),
            row.moduleType(), row.state(), row.rawState(), row.since(), row.fileName(),
            row.fileSize(), errorTexts);
    }

    private Data data(PartResult<byte[]> part, int maxChars) {
        if (!(part instanceof PartResult.Read<byte[]> read)) {
            return Data.unavailable(reason(part));
        }
        byte[] bytes = read.value();
        Classified classified = EntryContentClassifier.classify(bytes);
        return switch (classified.kind()) {
            case EMPTY -> Data.empty();
            case BINARY -> Data.binary(bytes.length, classified.detected());
            case TEXT -> {
                String masked = masked(classified.text().orElseThrow());
                yield Data.text(classified.charset().map(Charset::name).orElseThrow(),
                    bytes.length, masked.length(), QueueEntryBounds.cut(masked, maxChars));
            }
        };
    }

    private Variables variables(PartResult<List<RawVariable>> part) {
        if (!(part instanceof PartResult.Read<List<RawVariable>> read)) {
            return Variables.unavailable(reason(part));
        }
        List<RawVariable> all = read.value();
        List<Variable> items = new ArrayList<>();
        int budget = QueueEntryBounds.MAX_VALUES_CHARS;
        for (RawVariable variable : all.subList(0,
            Math.min(all.size(), QueueEntryBounds.MAX_VARIABLES))) {
            Variable item = variable(variable, budget);
            budget -= item.value().map(String::length).orElse(0);
            items.add(item);
        }
        return Variables.of(all.size(), items);
    }

    /** One variable; its value takes at most {@code budget} characters. */
    private Variable variable(RawVariable variable, int budget) {
        String name = knownSecrets.apply(variable.name());
        String shownName = QueueEntryBounds.cut(name, QueueEntryBounds.MAX_NAME_CHARS);
        Optional<Boolean> nameTruncated = shownName.length() < name.length()
            ? Optional.of(true) : Optional.empty();
        boolean system = SystemVariables.isSystem(variable.name());
        Optional<String> type = variable.type().map(knownSecrets);
        if (SecretTextMasker.secretVariableName(variable.name())) {
            return text(shownName, nameTruncated, system, type,
                SecretTextMasker.placeholder(VARIABLES_SCOPE, name), true, budget);
        }
        byte[] bytes = variable.value().getBytes(StandardCharsets.UTF_8);
        Classified classified = EntryContentClassifier.classify(bytes);
        if (classified.kind() == ContentKind.TEXT) {
            return text(shownName, nameTruncated, system, type,
                masked(classified.text().orElseThrow()), false, budget);
        }
        return new Variable(shownName, nameTruncated, system, type,
            QueueEntryResult.kindName(classified.kind()), Optional.empty(),
            Optional.of((long) bytes.length), classified.detected(), false, Optional.empty(),
            Optional.empty());
    }

    private static Variable text(String name, Optional<Boolean> nameTruncated, boolean system,
        Optional<String> type, String value, boolean masked, int budget) {
        String shown = QueueEntryBounds.cut(value,
            Math.min(QueueEntryBounds.MAX_VALUE_CHARS, budget));
        boolean truncated = shown.length() < value.length();
        return new Variable(name, nameTruncated, system, type,
            QueueEntryResult.kindName(ContentKind.TEXT), Optional.of(shown), Optional.empty(),
            Optional.empty(), masked,
            truncated ? Optional.of(value.length()) : Optional.empty(),
            truncated ? Optional.of(true) : Optional.empty());
    }

    /** The configured credentials replaced, then the secret-named values masked. */
    private String masked(String text) {
        return SecretTextMasker.mask(knownSecrets.apply(text));
    }

    private String reason(PartResult<?> part) {
        return switch (part) {
            case PartResult.NotFound<?> notFound -> NOT_FOUND_REASON;
            case PartResult.TooLarge<?> tooLarge -> "too large (" + tooLarge.bytes()
                + " bytes; limit " + QueueEntryBounds.MAX_DATA_BYTES / (1024 * 1024) + " MiB)";
            case PartResult.Failed<?> failed -> knownSecrets.apply(failed.reason());
            case PartResult.NotRequested<?> notRequested ->
                knownSecrets.apply(notRequested.reason());
            case PartResult.Read<?> read -> throw new IllegalArgumentException("Part was read");
        };
    }

    /**
     * The one INFO line of a call: inputs only once validated, the node and file name of the
     * selected row (also when a later step failed) or of the valid input, sizes of the parts
     * read; never content.
     */
    private void log(RawEntryRead raw, Optional<QueueEntryRef> selected,
        Optional<NodeId> failedOn, String processId, Optional<String> fileName, String outcome) {
        String node = selected.map(QueueEntryRef::node).or(() -> failedOn)
            .map(NodeId::toString).orElse(UNKNOWN);
        String process = processId != null && PROCESS_ID.matcher(processId).matches()
            ? processId : UNKNOWN;
        String file = selected.isPresent() ? selected.get().fileName()
            : fileName == null ? UNKNOWN
            : fileName.filter(QueueEntryRow::isValidFileName).orElse(UNKNOWN);
        String data = UNKNOWN;
        String variables = UNKNOWN;
        if (raw != null) {
            if (raw.content().data() instanceof PartResult.Read<byte[]> read) {
                data = String.valueOf(read.value().length);
            }
            if (raw.content().variables() instanceof PartResult.Read<List<RawVariable>> read) {
                variables = String.valueOf(read.value().size());
            }
        }
        LOG.info("{} on {}: process {}, file {}, data {} bytes, variables {}, outcome {}", TOOL,
            node, process, knownSecrets.apply(file), data, variables, outcome);
    }

    /**
     * Locates the entry and reads its content (steps 1–7 of the class description).
     *
     * @throws ToolErrorException as described there; no partial content on a failure of the
     *     whole content read
     */
    RawEntryRead readRaw(String target, String processId, Optional<String> fileName,
        OptionalInt maxChars) {
        return readRaw(target, processId, fileName, maxChars, row -> { });
    }

    /** As the method above; {@code selected} is told the entry as soon as it is selected. */
    private RawEntryRead readRaw(String target, String processId, Optional<String> fileName,
        OptionalInt maxChars, Consumer<QueueEntryRef> selected) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(processId, "processId");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(maxChars, "maxChars");
        int limit = validate(processId, fileName, maxChars);
        NodeId node = targets.resolveSingleServer(target);
        if (!optedIn.test(node)) {
            throw notConfigured(node);
        }
        VersionGate.Admission admission = gate.admitWithDetection(TOOL, node,
            Capability.QUEUE_ENTRY_READ, Capability.Kind.READ, Set.of(Channel.REST, Channel.CLI));
        // the gate admits QUEUE_ENTRY_READ only on a known major.minor
        InubitVersion version = admission.version().orElseThrow(() -> new IllegalStateException(
            TOOL + " on " + node + " was admitted without a version"));
        ProcessQueryPort port = processes.apply(node);
        QueueEntryRow row = select(node, processId, fileName, port.queueEntries(processId));
        selected.accept(QueueEntryRef.of(node, row));
        RawEntryContent content = read(node, row, version);
        if (content.data() instanceof PartResult.NotFound
            || content.variables() instanceof PartResult.NotFound) {
            boolean stillThere = port.queueEntries(processId).rows().stream()
                .anyMatch(again -> again.fileName().equals(row.fileName()));
            if (!stillThere) {
                throw new ToolErrorException(error(ErrorCode.NOT_FOUND, node, "Queue entry "
                        + row.fileName() + " of process " + processId + " left the Queue Manager"
                        + " of " + node + " (restarted, killed or deleted meanwhile)",
                    "The entry was restarted, killed or deleted between the lookup and the"
                        + " content read", LOOK_UP_AGAIN));
            }
        }
        return new RawEntryRead(node, row, content, limit, admission.warnings());
    }

    private RawEntryContent read(NodeId node, QueueEntryRow row, InubitVersion version) {
        boolean dataTooLarge = row.fileSize() > QueueEntryBounds.MAX_DATA_BYTES;
        Set<EntryPart> parts = dataTooLarge
            ? EnumSet.of(EntryPart.VARIABLES) : EnumSet.allOf(EntryPart.class);
        RawEntryContent content = contents.apply(node).read(QueueEntryRef.of(node, row), version,
            parts);
        return dataTooLarge
            ? new RawEntryContent(new PartResult.TooLarge<>(row.fileSize()), content.variables())
            : content;
    }

    /** The candidate rules of research D-9. */
    private static QueueEntryRow select(NodeId node, String processId, Optional<String> fileName,
        QueueEntries entries) {
        List<QueueEntryRow> rows = entries.rows();
        if (fileName.isPresent()) {
            QueueEntryRow named = rows.stream()
                .filter(row -> row.fileName().equals(fileName.get()))
                .findFirst()
                .orElseThrow(() -> new ToolErrorException(error(ErrorCode.NOT_FOUND, node,
                    "Queue entry " + fileName.get() + " of process " + processId + " is not in"
                        + " the Queue Manager of " + node + "; nothing was read"
                        + readRows(entries, ""),
                    "The file name does not belong to this process, or the entry left the Queue"
                        + " Manager meanwhile", LOOK_UP_AGAIN)));
            if (named.state() != ProcessState.ERROR) {
                throw notInError(node, processId, List.of(named), "");
            }
            return named;
        }
        if (rows.isEmpty()) {
            throw new ToolErrorException(error(ErrorCode.NOT_FOUND, node, "Process " + processId
                    + " is not (or no longer) in the Queue Manager of " + node
                    + "; nothing was read",
                "The instance finished, was restarted or killed meanwhile, or the id is wrong",
                LOOK_UP_AGAIN));
        }
        List<QueueEntryRow> candidates = rows.stream()
            .filter(row -> row.state() == ProcessState.ERROR)
            .toList();
        if (candidates.isEmpty()) {
            throw notInError(node, processId, rows, readRows(entries, " and none is in ERROR"));
        }
        if (candidates.size() > 1) {
            throw new ToolErrorException(error(ErrorCode.INVALID_INPUT, node, "Process "
                    + processId + " on " + node + " has " + candidates.size() + " ERROR entries: "
                    + listed(candidates.stream().map(QueueEntryService::candidate).toList(), "; ")
                    + readRows(entries, ""),
                "Several entries of the process are in ERROR (e.g. a sub-workflow and its"
                    + " caller)",
                "Repeat the call with fileName set to one of them"));
        }
        return candidates.get(0);
    }

    private static ToolErrorException notInError(NodeId node, String processId,
        List<QueueEntryRow> rows, String suffix) {
        return new ToolErrorException(error(ErrorCode.INVALID_INPUT, node, TOOL
                + " reads only ERROR entries; process " + processId + " on " + node + " has: "
                + listed(rows.stream().map(row -> row.rawState() + " (" + row.fileName() + ")")
                    .toList(), ", ") + suffix,
            "Only the content of an entry in state ERROR is read; no content was requested",
            "Wait until the entry is in ERROR, or use find_processes"));
    }

    /** File name, workflow, module (if known) and time of one candidate. */
    private static String candidate(QueueEntryRow row) {
        return row.fileName() + " (" + row.workflow()
            + row.module().map(module -> ", " + module).orElse("") + ", " + row.since() + ")";
    }

    /** The first {@value QueueEntryBounds#MAX_LISTED_CANDIDATES} items and how many more. */
    private static String listed(List<String> items, String separator) {
        int shown = Math.min(items.size(), QueueEntryBounds.MAX_LISTED_CANDIDATES);
        String text = String.join(separator, items.subList(0, shown));
        return items.size() > shown
            ? text + separator + "and " + (items.size() - shown) + " more" : text;
    }

    /** "; only N of M rows were read" (with {@code tail}) when the row cap was hit, else "". */
    private static String readRows(QueueEntries entries, String tail) {
        return entries.truncated()
            ? "; only " + entries.rows().size() + " of " + entries.total() + " rows were read"
                + tail
            : "";
    }

    private ToolErrorException notConfigured(NodeId node) {
        String group = targets.terms().render("{group}") + " " + node.group();
        return new ToolErrorException(error(ErrorCode.NOT_CONFIGURED, node, TOOL
                + " is not enabled for " + group + ": unsupported.queueEntryRead is not set",
            "Reading Queue Manager entries uses an unsupported, undocumented INUBIT interface"
                + " and is off unless the " + targets.terms().render("{group}") + " opts in",
            "Set unsupported.queueEntryRead: true on " + group + " and restart the MCP client"
                + " (unsupported INUBIT functionality, see docs/setup.md \"Unsupported"
                + " functions\")"));
    }

    /** @return the text limit, default applied */
    private static int validate(String processId, Optional<String> fileName,
        OptionalInt maxChars) {
        if (!PROCESS_ID.matcher(processId).matches()) {
            throw invalid("Invalid processId " + Names.quote(processId),
                "A processId is the decimal Queue Manager id (workflowId, 1–19 digits without"
                    + " leading zero); a UUID globalProcessId is not accepted",
                "Use the processId from find_processes");
        }
        if (fileName.isPresent() && !QueueEntryRow.isValidFileName(fileName.get())) {
            throw invalid("Invalid fileName: expected 1–255 characters without '/', '\\', NUL"
                    + " or other control characters",
                "fileName is the queue file name as query_logs with logType queueLog lists it"
                    + " in fields.fileName",
                "Copy fileName exactly from query_logs (logType queueLog), or leave it out");
        }
        int limit = maxChars.orElse(QueueEntryBounds.DEFAULT_MAX_CHARS);
        if (limit < 1 || limit > QueueEntryBounds.MAX_MAX_CHARS) {
            throw invalid("Invalid maxChars " + limit + " (1–" + QueueEntryBounds.MAX_MAX_CHARS
                    + ")",
                "maxChars is the number of characters of the data stream text returned",
                "Use an integer from 1 to " + QueueEntryBounds.MAX_MAX_CHARS + ", or leave it out"
                    + " for " + QueueEntryBounds.DEFAULT_MAX_CHARS);
        }
        return limit;
    }

    private static ToolError error(ErrorCode code, NodeId node, String message,
        String likelyCause, String nextStep) {
        return ToolError.of(code, message, likelyCause, nextStep).withNode(node);
    }

    private static ToolErrorException invalid(String message, String likelyCause,
        String nextStep) {
        return new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT, message,
            likelyCause, nextStep));
    }
}
