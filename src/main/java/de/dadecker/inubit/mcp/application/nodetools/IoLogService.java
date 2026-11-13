package de.dadecker.inubit.mcp.application.nodetools;

import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.application.VersionGate;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.CredentialState;
import de.dadecker.inubit.mcp.domain.model.Durations;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.QueueEntryBounds;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.node.IoLogBounds;
import de.dadecker.inubit.mcp.domain.model.node.IoLogEntry;
import de.dadecker.inubit.mcp.domain.model.node.IoLogListing;
import de.dadecker.inubit.mcp.domain.model.node.IoLogResult;
import de.dadecker.inubit.mcp.domain.model.node.IoMessageRef;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The use case behind {@code read_io_log} (feature 024, FR-020, research D-8, D-22; decision flow
 * and texts per contracts/mcp-tools-delta.md). The listing of a run; one call, nothing sent before
 * step 4:
 * <ol>
 *   <li>The inputs: {@code workflow} 1–200 characters without control characters, {@code @@@} or
 *       {@code ###}; {@code processId} 1–64 of {@code [0-9A-Za-z_-]}; {@code module} 1–200
 *       characters without control characters; {@code maxChars} 1–100,000; {@code message} a
 *       22-character ref. Else {@code INVALID_INPUT}; a refused text is not echoed.</li>
 *   <li>{@link TargetResolver#resolveSingleServer}: exactly one server.</li>
 *   <li>The group's opt-in {@code unsupported.ioLogRead}; without it {@code NOT_CONFIGURED}
 *       (no detection, no helper).</li>
 *   <li>{@link VersionGate#admitWithDetection} for {@code IO_LOG_READ} over StartCLI's client:
 *       {@code UNSUPPORTED_VERSION} or the detection's error.</li>
 *   <li>The call deadline, now + the server's {@code cliTimeout}; within it the server's login
 *       credentials ({@link GatewayFactory#prepareCredentials}); still being read →
 *       {@code TIMEOUT} naming {@code cliTimeout}.</li>
 *   <li>One {@link de.dadecker.inubit.mcp.domain.port.IoLogPort#list} run with the deadline; its
 *       errors pass through (one without a node names the server).</li>
 *   <li>The {@code module} filter ({@link IoLogListing#filter}), the first
 *       {@value IoLogBounds#MAX_LISTED} matching entries with {@code total}, {@code matching} and
 *       {@code truncated}, the hints of an empty run and of entries listed with their raw module
 *       part, and one INFO line with the server, the process ID, the entry count and the outcome;
 *       never a name or other value.</li>
 * </ol>
 * The message read ({@code message} set) is not implemented yet: a valid {@code message} is
 * refused with {@link UnsupportedOperationException} after step 1, before anything is sent.
 *
 * <p>Thread-safe; it keeps no state of its own.
 */
public final class IoLogService {

    /** The tool name, the start of the gate's refusals and of the INFO line. */
    static final String TOOL = "read_io_log";

    private static final Logger LOG = LoggerFactory.getLogger(IoLogService.class);
    private static final String UNKNOWN = "-";
    /** The {@code processId} rule of {@code query_logs}. */
    private static final Pattern PROCESS_ID = Pattern.compile("[0-9A-Za-z_-]{1,64}");

    private final TargetResolver targets;
    private final Predicate<NodeId> optedIn;
    private final VersionGate gate;
    private final GatewayFactory gateways;
    private final Function<NodeId, Duration> cliTimeouts;
    private final LongSupplier nanoTime;

    /**
     * @param optedIn     whether the node's group sets {@code unsupported.ioLogRead: true}
     * @param gateways    the server's credentials and its I/O log port
     * @param cliTimeouts the {@code cliTimeout} of a node, the listing's budget
     * @param nanoTime    the clock of the call deadline ({@link System#nanoTime()})
     */
    public IoLogService(TargetResolver targets, Predicate<NodeId> optedIn, VersionGate gate,
        GatewayFactory gateways, Function<NodeId, Duration> cliTimeouts, LongSupplier nanoTime) {
        this.targets = Objects.requireNonNull(targets, "targets");
        this.optedIn = Objects.requireNonNull(optedIn, "optedIn");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.gateways = Objects.requireNonNull(gateways, "gateways");
        this.cliTimeouts = Objects.requireNonNull(cliTimeouts, "cliTimeouts");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /**
     * Lists the run's logged messages as the class description says; logs one INFO line either
     * way.
     *
     * @throws ToolErrorException as described in the class description
     */
    public IoLogResult read(IoLogRequest request) {
        Objects.requireNonNull(request, "request");
        NodeId node = null;
        IoLogListing listing = null;
        String outcome = ErrorCode.INTERNAL.name();
        try {
            validate(request);
            if (request.message().isPresent()) {
                throw new UnsupportedOperationException(TOOL + " with message is not implemented");
            }
            node = targets.resolveSingleServer(request.target());
            if (!optedIn.test(node)) {
                throw notConfigured(node);
            }
            VersionGate.Admission admission = gate.admitWithDetection(TOOL, node,
                Capability.IO_LOG_READ, Capability.Kind.READ, Set.of(Channel.CLI));
            listing = list(node, admission, request);
            IoLogResult result = result(node, request, listing, admission);
            outcome = "OK";
            return result;
        } catch (ToolErrorException e) {
            if (node == null) {
                node = e.error().node().orElse(null);
            }
            outcome = e.error().code().name();
            throw e;
        } finally {
            log(node, request.processId(), listing, outcome);
        }
    }

    /** Steps 5 and 6: the call deadline, the server's login credentials, the port. */
    private IoLogListing list(NodeId node, VersionGate.Admission admission,
        IoLogRequest request) {
        // the gate admits IO_LOG_READ only on a known major.minor
        InubitVersion version = admission.version().orElseThrow(() -> new IllegalStateException(
            TOOL + " on " + node + " was admitted without a version"));
        Duration budget = cliTimeouts.apply(node);
        long deadlineNanos = nanoTime.getAsLong() + budget.toNanos();
        try {
            if (gateways.prepareCredentials(node, remaining(deadlineNanos))
                == CredentialState.PENDING) {
                throw new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT, TOOL + " on " + node
                        + " did not finish within cliTimeout (" + Durations.human(budget)
                        + "): the login credentials of " + node + " were not read in time",
                    "The first read of the login credentials from their store took longer than"
                        + " cliTimeout; it continues, so a later call finds them",
                    "Retry in a moment; raise cliTimeout of " + node + " if the credential store"
                        + " is just slow").withNode(node));
            }
            return gateways.ioLog(node).list(request.processId(), request.workflow(), version,
                deadlineNanos);
        } catch (ToolErrorException e) {
            // an adapter error without a node (e.g. the PortSet's CLI_UNAVAILABLE) names the server
            throw e.error().node().isPresent() ? e
                : new ToolErrorException(e.error().withNode(node));
        }
    }

    /** Step 7: filter, cap, hints and warnings. */
    private static IoLogResult result(NodeId node, IoLogRequest request, IoLogListing listing,
        VersionGate.Admission admission) {
        List<IoLogEntry> matching = listing.filter(request.module());
        List<IoLogEntry> listed = matching.size() > IoLogBounds.MAX_LISTED
            ? matching.subList(0, IoLogBounds.MAX_LISTED) : matching;
        List<String> hints = new ArrayList<>();
        if (listing.entries().isEmpty()) {
            hints.add(IoLogResult.EMPTY_LISTING_HINT);
        }
        if (listed.stream().anyMatch(entry -> !entry.moduleParsed())) {
            hints.add(IoLogResult.RAW_MODULE_HINT);
        }
        List<String> warnings = new ArrayList<>();
        warnings.add(IoLogResult.WARNING);
        warnings.addAll(admission.warnings());
        return new IoLogResult(node, listing.answeringNode(), request.workflow(),
            request.processId(), listing.entries().size(), matching.size(), listed,
            listed.size() < matching.size(), hints, warnings);
    }

    /**
     * The one INFO line of a call (D-22): the server once known, the process ID once valid, the
     * entry count once listed; never a name or other value.
     */
    private static void log(NodeId node, String processId, IoLogListing listing,
        String outcome) {
        LOG.info("{} on {}: process {}, listing {}, outcome {}", TOOL,
            node == null ? UNKNOWN : node.toString(),
            PROCESS_ID.matcher(processId).matches() ? processId : UNKNOWN,
            listing == null ? UNKNOWN : String.valueOf(listing.entries().size()), outcome);
    }

    /** What is left of the call deadline, never negative. */
    private Duration remaining(long deadlineNanos) {
        long left = deadlineNanos - nanoTime.getAsLong();
        return left > 0 ? Duration.ofNanos(left) : Duration.ZERO;
    }

    /** Step 1 (D-8); no refused text is echoed. */
    private static void validate(IoLogRequest request) {
        String workflow = request.workflow();
        if (!isText(workflow, IoLogBounds.MAX_WORKFLOW_CHARS) || workflow.contains("@@@")
            || workflow.contains("###")) {
            throw invalid("Invalid workflow: expected 1–" + IoLogBounds.MAX_WORKFLOW_CHARS
                    + " characters without control characters, '@@@' or '###'",
                "workflow is the process name as query_logs returns it",
                "Copy the process name exactly from query_logs");
        }
        if (!PROCESS_ID.matcher(request.processId()).matches()) {
            throw invalid("Invalid processId: expected 1–64 characters of A-Z, a-z, 0-9, '_'"
                    + " and '-'",
                "processId is the process ID of the run as query_logs returns it",
                "Copy the process ID exactly from query_logs");
        }
        if (request.module().isPresent()
            && !isText(request.module().get(), IoLogBounds.MAX_MODULE_CHARS)) {
            throw invalid("Invalid module: expected 1–" + IoLogBounds.MAX_MODULE_CHARS
                    + " characters without control characters",
                "module is a module name or module ID as read_io_log lists them",
                "Use a moduleName or moduleId from the listing, or leave module out");
        }
        OptionalInt maxChars = request.maxChars();
        if (maxChars.isPresent()
            && (maxChars.getAsInt() < 1 || maxChars.getAsInt() > QueueEntryBounds.MAX_MAX_CHARS)) {
            throw invalid("Invalid maxChars " + maxChars.getAsInt() + " (1–"
                    + QueueEntryBounds.MAX_MAX_CHARS + ")",
                "maxChars is the number of characters of the message text returned",
                "Use an integer from 1 to " + QueueEntryBounds.MAX_MAX_CHARS + ", or leave it out"
                    + " for " + QueueEntryBounds.DEFAULT_MAX_CHARS);
        }
        Optional<String> message = request.message();
        if (message.isPresent() && !IoMessageRef.isValid(message.get())) {
            throw invalid("Invalid message: expected the 22-character ref of an entry of this"
                    + " run's listing",
                "message is the ref of an entry read_io_log listed for this run",
                "Call read_io_log without message and use a listed ref");
        }
    }

    /** 1 to {@code maxChars} characters (code points), none of them a C0 or C1 control. */
    private static boolean isText(String text, int maxChars) {
        int chars = text.codePointCount(0, text.length());
        return chars >= 1 && chars <= maxChars
            && text.chars().noneMatch(c -> c <= 0x1F || (c >= 0x7F && c <= 0x9F));
    }

    private static ToolErrorException invalid(String message, String likelyCause,
        String nextStep) {
        return new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT, message,
            likelyCause, nextStep));
    }

    private ToolErrorException notConfigured(NodeId node) {
        String group = targets.terms().render("{group}") + " " + node.group();
        return new ToolErrorException(ToolError.of(ErrorCode.NOT_CONFIGURED, TOOL
                + " is not enabled for " + group + ": unsupported.ioLogRead is not set",
            "Reading the input/output logging uses an unsupported, undocumented INUBIT interface"
                + " and is off unless the " + targets.terms().render("{group}") + " opts in",
            "Set unsupported.ioLogRead: true on " + group + " and restart the MCP client"
                + " (unsupported INUBIT functionality, see docs/setup.md \"Unsupported"
                + " functions\")").withNode(node));
    }
}
