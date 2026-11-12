package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.CredentialState;
import de.dadecker.inubit.mcp.domain.model.Durations;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ServerLogBounds;
import de.dadecker.inubit.mcp.domain.model.ServerLogKind;
import de.dadecker.inubit.mcp.domain.model.ServerLogResult;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.DownloadedLog;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * {@code download_server_log} (feature 021): downloads one server log of one node into a private
 * directory, in the order of contracts/mcp-tools-delta.md "Decision flow"; nothing is sent to
 * INUBIT and nothing is created locally before steps 1–5 passed.
 * <ol>
 *   <li>The input schema's checks again (defence in depth) → {@code INVALID_INPUT} naming the
 *       field; the {@code contains} text is never echoed.</li>
 *   <li>{@link TargetResolver#resolveSingleServer}: exactly one server.</li>
 *   <li>{@code log: webservice} needs the group's {@code unsupported.serverLogRead} →
 *       {@code NOT_CONFIGURED} before any detection or request.</li>
 *   <li>The folder rules ({@link TargetFolderRules}, {@code INVALID_INPUT}, nothing created).</li>
 *   <li>A refusal of steps 1–4 is one {@code REFUSED} audit record; otherwise the {@code PENDING}
 *       record (unwritable → {@code INTERNAL}, nothing sent).</li>
 *   <li>{@link VersionGate#admitWithDetection}: {@code UNSUPPORTED_VERSION} closes the record
 *       with {@code REFUSED}; a detection error (also {@code MAINTENANCE_MODE}) passes through and
 *       closes it with {@code FAILED}.</li>
 *   <li>The call deadline, now + the node's {@code cliExportTimeout} (research D-15): the
 *       server's login credentials are prepared within it ({@link
 *       GatewayFactory#prepareCredentials}; still being read → {@code TIMEOUT} naming
 *       {@code cliExportTimeout}, a failed fetch passes through), then {@link
 *       de.dadecker.inubit.mcp.domain.port.ServerLogPort#download} gets the deadline. Every
 *       failure from here on closes the record with {@code FAILED}.</li>
 * </ol>
 */
public final class ServerLogService {

    /** What a {@code download_server_log} target folder holds, for the folder rules' texts. */
    static final TargetFolderRules.Subject FOLDER_SUBJECT = new TargetFolderRules.Subject(
        "the log file", "log files with clear-text content",
        "the log file (with clear-text content) will be synchronized to the cloud");

    private static final int MAX_FOLDER_CHARS = 4_096;
    private static final Pattern CONTROL = Pattern.compile("\\p{Cc}");
    private static final String SCHEMA_STEP = "Correct the parameter as the tool's input schema"
        + " describes";

    private final TargetResolver targets;
    private final Predicate<NodeId> optedIn;
    private final Function<NodeId, Optional<String>> accounts;
    private final TargetFolderRules folders;
    private final VersionGate gate;
    private final GatewayFactory gateways;
    private final Function<NodeId, Duration> exportTimeouts;
    private final ServerLogAudit.Dependencies audit;
    private final LongSupplier nanoTime;

    /**
     * @param optedIn        whether the node's group sets {@code unsupported.serverLogRead: true}
     * @param accounts       the INUBIT account of a node's server, for the audit
     * @param folders        the rules of the target folder
     * @param gate           admits the download ({@code TRACE_LOG_READ} or
     *                       {@code WEBSERVICE_LOG_READ} over StartCLI)
     * @param gateways       the server's credentials and its server-log port
     * @param exportTimeouts the {@code cliExportTimeout} of a node, the call's budget
     * @param audit          what every call's audit shares
     * @param nanoTime       the clock of the call deadline ({@link System#nanoTime()})
     */
    public ServerLogService(TargetResolver targets, Predicate<NodeId> optedIn,
        Function<NodeId, Optional<String>> accounts, TargetFolderRules folders, VersionGate gate,
        GatewayFactory gateways, Function<NodeId, Duration> exportTimeouts,
        ServerLogAudit.Dependencies audit, LongSupplier nanoTime) {
        this.targets = Objects.requireNonNull(targets, "targets");
        this.optedIn = Objects.requireNonNull(optedIn, "optedIn");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.folders = Objects.requireNonNull(folders, "folders");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.gateways = Objects.requireNonNull(gateways, "gateways");
        this.exportTimeouts = Objects.requireNonNull(exportTimeouts, "exportTimeouts");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /**
     * Downloads the requested log as the class description says.
     *
     * @throws ToolErrorException a refusal of steps 1–4, {@code INTERNAL} for an unwritable audit
     *     record that must precede the download, the gate's refusal or the detection's error,
     *     {@code TIMEOUT} for credentials not read within the call deadline, and the errors of the
     *     credential fetch and of the download; when the final audit record could not be written,
     *     the message names the {@code auditId}
     */
    public ServerLogResult download(ServerLogRequest request) {
        ServerLogAudit record = new ServerLogAudit(audit, request.target(), request.log(),
            request.targetFolder(), request.tailLines(), request.maxChars(),
            request.contains().isPresent(), request.mcpClient());
        Checked checked;
        try {
            checked = check(request, record);
        } catch (ToolErrorException e) {
            throw record.refused(e.error());
        }
        record.pending();
        return run(record, checked);
    }

    /** The outcome of steps 1–4. */
    private record Checked(NodeId node, ServerLogKind kind, TargetFolderRules.CheckedFolder folder) {
    }

    /** Steps 1–4, first failure wins. */
    private Checked check(ServerLogRequest request, ServerLogAudit record) {
        ServerLogKind kind = checkInputs(request);
        NodeId node = targets.resolveSingleServer(request.target());
        record.node(node, accounts.apply(node));
        if (kind == ServerLogKind.WEBSERVICE && !optedIn.test(node)) {
            throw notConfigured(node);
        }
        try {
            return new Checked(node, kind, folders.check(request.targetFolder(), FOLDER_SUBJECT));
        } catch (ToolErrorException e) {
            throw new ToolErrorException(e.error().withNode(node));
        }
    }

    /** The input schema's limits again; returns the log kind. */
    private static ServerLogKind checkInputs(ServerLogRequest request) {
        ServerLogKind kind;
        try {
            kind = ServerLogKind.fromWire(request.log());
        } catch (IllegalArgumentException e) {
            throw invalid("Invalid log: expected trace or webservice",
                "log names the server log: trace (trace.log) or webservice (webservice.log)",
                "Use log trace or webservice");
        }
        int folderChars = codePoints(request.targetFolder());
        if (folderChars < 1 || folderChars > MAX_FOLDER_CHARS) {
            throw invalid("Invalid targetFolder: expected 1–" + MAX_FOLDER_CHARS + " characters",
                "targetFolder is the absolute path of a local folder for the log file",
                SCHEMA_STEP);
        }
        checkRange("tailLines", request.tailLines(), ServerLogBounds.MAX_TAIL_LINES,
            ServerLogBounds.DEFAULT_TAIL_LINES,
            "tailLines is the number of newest (matching) lines of the excerpt");
        if (request.contains().isPresent()) {
            String contains = request.contains().get();
            int chars = codePoints(contains);
            if (chars < 1 || chars > ServerLogBounds.MAX_CONTAINS_CHARS
                || CONTROL.matcher(contains).find()) {
                throw invalid("Invalid contains: expected 1–" + ServerLogBounds.MAX_CONTAINS_CHARS
                        + " characters without control characters",
                    "contains is plain text the excerpt lines must contain",
                    "Pass 1 to " + ServerLogBounds.MAX_CONTAINS_CHARS + " characters on one line,"
                        + " or leave contains out");
            }
        }
        checkRange("maxChars", request.maxChars(), ServerLogBounds.MAX_MAX_CHARS,
            ServerLogBounds.DEFAULT_MAX_CHARS,
            "maxChars is the number of characters of the excerpt lines returned");
        return kind;
    }

    private static void checkRange(String name, OptionalInt value, int max, int defaultValue,
        String likelyCause) {
        if (value.isPresent() && (value.getAsInt() < 1 || value.getAsInt() > max)) {
            throw invalid("Invalid " + name + " " + value.getAsInt() + " (1–" + max + ")",
                likelyCause, "Use an integer from 1 to " + max + ", or leave it out for "
                    + defaultValue);
        }
    }

    private static int codePoints(String text) {
        return text.codePointCount(0, text.length());
    }

    private ToolErrorException notConfigured(NodeId node) {
        String group = targets.terms().render("{group}") + " " + node.group();
        return new ToolErrorException(ToolError.of(ErrorCode.NOT_CONFIGURED,
            ServerLogAudit.CAPABILITY + " for the web service log is not enabled for " + group
                + ": unsupported.serverLogRead is not set",
            "Downloading the web service log uses an unsupported, undocumented INUBIT interface"
                + " and is off unless the " + targets.terms().render("{group}") + " opts in",
            "Set unsupported.serverLogRead: true on " + group + " and restart the MCP client"
                + " (unsupported INUBIT functionality, see docs/setup.md \"Unsupported"
                + " functions\")").withNode(node));
    }

    /**
     * Steps 6 and 7 after the {@code PENDING} record: every failure closes the record, the gate's
     * {@code UNSUPPORTED_VERSION} with {@code REFUSED}, everything else with {@code FAILED}.
     */
    private ServerLogResult run(ServerLogAudit record, Checked checked) {
        VersionGate.Admission admission;
        try {
            admission = gate.admitWithDetection(ServerLogAudit.CAPABILITY, checked.node(),
                capability(checked.kind()), Capability.Kind.READ, Set.of(Channel.CLI));
        } catch (ToolErrorException e) {
            throw closed(record, e, e.error().code() == ErrorCode.UNSUPPORTED_VERSION
                ? AuditOutcome.REFUSED : AuditOutcome.FAILED);
        } catch (RuntimeException e) {
            throw unexpected(record, e);
        }
        try {
            return fetch(record, checked, admission);
        } catch (ToolErrorException e) {
            throw closed(record, e, AuditOutcome.FAILED);
        } catch (RuntimeException e) {
            throw unexpected(record, e);
        }
    }

    /** Step 7: the call deadline, the server's login credentials, the download. */
    private ServerLogResult fetch(ServerLogAudit record, Checked checked,
        VersionGate.Admission admission) {
        NodeId node = checked.node();
        // the gate admits the server logs only on a known major.minor
        InubitVersion version = admission.version().orElseThrow(() -> new IllegalStateException(
            ServerLogAudit.CAPABILITY + " on " + node + " was admitted without a version"));
        Duration budget = exportTimeouts.apply(node);
        long deadlineNanos = nanoTime.getAsLong() + budget.toNanos();
        if (gateways.prepareCredentials(node, remaining(deadlineNanos))
            == CredentialState.PENDING) {
            throw new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT, "The "
                    + checked.kind().wireName() + " log download from " + node + " did not finish"
                    + " within cliExportTimeout (" + Durations.human(budget) + "): the login"
                    + " credentials of " + node + " were not read in time",
                "The first read of the login credentials from their store took longer than"
                    + " cliExportTimeout; it continues, so a later call finds them",
                "Retry in a moment; raise cliExportTimeout if the credential store is just slow;"
                    + " large logs need a larger cliExportTimeout").withNode(node));
        }
        try (DownloadedLog downloaded = gateways.serverLogs(node).download(checked.kind(),
            version, deadlineNanos)) {
            record.downloaded(downloaded.size(), downloaded.inubitNode());
            return excerptAndPlace(checked, admission, downloaded);
        }
    }

    /**
     * The excerpt, the placement in the target folder and the result follow in T028; until then
     * a download ends in {@code INTERNAL} (the tool is not announced before T031).
     */
    private static ServerLogResult excerptAndPlace(Checked checked,
        VersionGate.Admission admission, DownloadedLog downloaded) {
        throw new ToolErrorException(ToolError.of(ErrorCode.INTERNAL, ServerLogAudit.CAPABILITY
                + ": the excerpt and placement are not wired yet",
            "An internal error of the INUBIT MCP server", "Download the log in the Workbench")
            .withNode(checked.node()));
    }

    /** What is left of the call deadline, never negative. */
    private Duration remaining(long deadlineNanos) {
        long left = deadlineNanos - nanoTime.getAsLong();
        return left > 0 ? Duration.ofNanos(left) : Duration.ZERO;
    }

    /**
     * Closes the record with {@code outcome}; returns {@code e}, or its error with the outcome
     * warning appended to the message if the final record could not be written.
     */
    private static ToolErrorException closed(ServerLogAudit record, ToolErrorException e,
        AuditOutcome outcome) {
        ToolError error = e.error();
        Optional<String> warning = record.outcome(outcome, error.code() + ": " + error.message());
        if (warning.isEmpty()) {
            return e;
        }
        return new ToolErrorException(new ToolError(error.code(), error.message() + ". "
            + warning.get(), error.likelyCause(), error.nextStep(), error.node(),
            error.excerpt()));
    }

    /** Closes the record of an unexpected failure with {@code FAILED} and returns it. */
    private static RuntimeException unexpected(ServerLogAudit record, RuntimeException e) {
        record.outcome(AuditOutcome.FAILED, ErrorCode.INTERNAL + ": "
            + e.getClass().getSimpleName());
        return e;
    }

    private static Capability capability(ServerLogKind kind) {
        return switch (kind) {
            case TRACE -> Capability.TRACE_LOG_READ;
            case WEBSERVICE -> Capability.WEBSERVICE_LOG_READ;
        };
    }

    private static ToolErrorException invalid(String message, String likelyCause,
        String nextStep) {
        return new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT, message, likelyCause,
            nextStep));
    }
}
