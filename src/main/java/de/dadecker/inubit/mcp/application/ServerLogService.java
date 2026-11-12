package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.CredentialState;
import de.dadecker.inubit.mcp.domain.model.Durations;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.LogExcerpt;
import de.dadecker.inubit.mcp.domain.model.LogPresence;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ServerLogBounds;
import de.dadecker.inubit.mcp.domain.model.ServerLogKind;
import de.dadecker.inubit.mcp.domain.model.ServerLogResult;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.ConfiguredSecrets;
import de.dadecker.inubit.mcp.domain.port.ConfiguredSecrets.Readiness;
import de.dadecker.inubit.mcp.domain.port.DownloadedLog;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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
import java.util.stream.Collectors;

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
 *   <li>The credentials of every node, for masking, within {@code min(prepareBound, remaining)}
 *       (research D-11): not all known → the excerpt is withheld with a hint and no permit is
 *       taken; otherwise the {@link ExcerptPermit} within the remaining deadline (not free →
 *       {@code TIMEOUT}) and {@link LogExcerpt#build} over the scanned window.</li>
 *   <li>{@link LogFileWriter#place} as {@code <group>-<node>-<log>-<yyyyMMdd-HHmmss>.log} at the
 *       UTC second the download finished, the final {@code EXECUTED} record, the result. The
 *       private directory is deleted on every path; on a failure no file of the call stays in
 *       the folder.</li>
 * </ol>
 */
public final class ServerLogService {

    /** What a {@code download_server_log} target folder holds, for the folder rules' texts. */
    static final TargetFolderRules.Subject FOLDER_SUBJECT = new TargetFolderRules.Subject(
        "the log file", "log files with clear-text content",
        "the log file (with clear-text content) will be synchronized to the cloud");

    private static final String CLEAR_TEXT_WARNING = "The log file contains clear-text content";
    private static final String UNSUPPORTED_WARNING =
        "Read through an unsupported, undocumented INUBIT interface; diagnosis only";
    /** The time in the file name: when the download finished, UTC (D-7 step 5). */
    private static final DateTimeFormatter FILE_TIME =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
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
    private final ConfiguredSecrets secrets;
    private final Duration prepareBound;
    private final ExcerptPermit permit;
    private final LogFileWriter writer;
    private final ServerLogAudit.Dependencies audit;
    private final Clock clock;
    private final LongSupplier nanoTime;

    /**
     * @param optedIn        whether the node's group sets {@code unsupported.serverLogRead: true}
     * @param accounts       the INUBIT account of a node's server, for the audit
     * @param folders        the rules of the target folder
     * @param gate           admits the download ({@code TRACE_LOG_READ} or
     *                       {@code WEBSERVICE_LOG_READ} over StartCLI)
     * @param gateways       the server's credentials and its server-log port
     * @param exportTimeouts the {@code cliExportTimeout} of a node, the call's budget
     * @param secrets        the credentials of every node of the profile, masked in the excerpt
     *                       (the instance feature 019's {@code KnowledgeService} uses)
     * @param prepareBound   the longest wait for {@code secrets} ({@code ProfileSecrets.timeout()})
     * @param permit         the process's one excerpt permit
     * @param writer         places the log in the target folder
     * @param audit          what every call's audit shares
     * @param clock          the time the download finished ({@code downloadedAt}, the file name)
     * @param nanoTime       the clock of the call deadline ({@link System#nanoTime()})
     */
    public ServerLogService(TargetResolver targets, Predicate<NodeId> optedIn,
        Function<NodeId, Optional<String>> accounts, TargetFolderRules folders, VersionGate gate,
        GatewayFactory gateways, Function<NodeId, Duration> exportTimeouts,
        ConfiguredSecrets secrets, Duration prepareBound, ExcerptPermit permit,
        LogFileWriter writer, ServerLogAudit.Dependencies audit, Clock clock,
        LongSupplier nanoTime) {
        this.targets = Objects.requireNonNull(targets, "targets");
        this.optedIn = Objects.requireNonNull(optedIn, "optedIn");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.folders = Objects.requireNonNull(folders, "folders");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.gateways = Objects.requireNonNull(gateways, "gateways");
        this.exportTimeouts = Objects.requireNonNull(exportTimeouts, "exportTimeouts");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.prepareBound = Objects.requireNonNull(prepareBound, "prepareBound");
        this.permit = Objects.requireNonNull(permit, "permit");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /**
     * Downloads the requested log as the class description says.
     *
     * @throws ToolErrorException a refusal of steps 1–4, {@code INTERNAL} for an unwritable audit
     *     record that must precede the download, the gate's refusal or the detection's error,
     *     {@code TIMEOUT} for credentials not read or an excerpt permit not free within the call
     *     deadline, the errors of the credential fetch, of the download and of the placement, and
     *     {@code INTERNAL} for an unreadable private copy; when the final audit record could not be
     *     written, the message names the {@code auditId}
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

    /** The outcome of steps 1–4, with the excerpt's inputs (defaults applied). */
    private record Checked(NodeId node, ServerLogKind kind, TargetFolderRules.CheckedFolder folder,
        int tailLines, Optional<String> contains, int maxChars) {
    }

    /** Steps 1–4, first failure wins. */
    private Checked check(ServerLogRequest request, ServerLogAudit record) {
        ServerLogKind kind = checkInputs(request);
        NodeId node = targets.resolveSingleServer(request.target());
        record.node(node, accounts.apply(node));
        if (kind == ServerLogKind.WEBSERVICE && !optedIn.test(node)) {
            throw notConfigured(node);
        }
        TargetFolderRules.CheckedFolder folder;
        try {
            folder = folders.check(request.targetFolder(), FOLDER_SUBJECT);
        } catch (ToolErrorException e) {
            throw new ToolErrorException(e.error().withNode(node));
        }
        return new Checked(node, kind, folder,
            request.tailLines().orElse(ServerLogBounds.DEFAULT_TAIL_LINES), request.contains(),
            request.maxChars().orElse(ServerLogBounds.DEFAULT_MAX_CHARS));
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
            // an adapter error without a node (e.g. the PortSet's CLI_UNAVAILABLE) names the server
            ToolErrorException named = e.error().node().isPresent() ? e
                : new ToolErrorException(e.error().withNode(checked.node()));
            throw closed(record, named, AuditOutcome.FAILED);
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
            throw new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT,
                notFinished(checked, budget) + ": the login credentials of " + node
                    + " were not read in time",
                "The first read of the login credentials from their store took longer than"
                    + " cliExportTimeout; it continues, so a later call finds them",
                "Retry in a moment; raise cliExportTimeout if the credential store is just slow;"
                    + " large logs need a larger cliExportTimeout").withNode(node));
        }
        try (DownloadedLog downloaded = gateways.serverLogs(node).download(checked.kind(),
            version, deadlineNanos)) {
            Instant downloadedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
            record.downloaded(downloaded.size(), downloaded.inubitNode());
            return excerptAndPlace(record, checked, admission, downloaded, downloadedAt, budget,
                deadlineNanos);
        }
    }

    /**
     * Steps 8 and 9: the credentials of every node for masking (not all known → the excerpt is
     * withheld and no permit is taken), the excerpt, the placement under the name of
     * {@code downloadedAt}, the final record and the result.
     */
    private ServerLogResult excerptAndPlace(ServerLogAudit record, Checked checked,
        VersionGate.Admission admission, DownloadedLog downloaded, Instant downloadedAt,
        Duration budget, long deadlineNanos) {
        List<String> hints = new ArrayList<>();
        if (downloaded.size() == 0) {
            hints.add(emptyHint(checked.kind(), downloaded.presence()));
        }
        Duration left = remaining(deadlineNanos);
        Optional<LogExcerpt> excerpt;
        if (secrets.prepare(left.compareTo(prepareBound) < 0 ? left : prepareBound)
            instanceof Readiness.NotReady notReady) {
            excerpt = Optional.empty();
            hints.add(withheldHint(notReady));
        } else {
            excerpt = Optional.of(excerpt(checked, downloaded, budget, deadlineNanos));
        }
        LogFileWriter.Placed placed = place(checked, downloaded, downloadedAt);
        record.file(placed.file());
        List<String> warnings = new ArrayList<>();
        if (checked.kind() == ServerLogKind.WEBSERVICE) {
            warnings.add(UNSUPPORTED_WARNING);
        }
        warnings.add(CLEAR_TEXT_WARNING);
        warnings.addAll(checked.folder().warnings());
        warnings.addAll(admission.warnings());
        record.outcome(AuditOutcome.EXECUTED,
            excerpt.isPresent() ? "written" : "written; excerpt withheld").ifPresent(warnings::add);
        return new ServerLogResult(checked.node(), checked.kind(), downloaded.inubitNode(),
            placed.file(), placed.size(), downloadedAt, excerpt, hints, warnings);
    }

    /**
     * Builds the excerpt under the process's excerpt permit, waiting for it at most until the
     * call deadline; once held, the build runs to its end (FR-013).
     */
    private LogExcerpt excerpt(Checked checked, DownloadedLog downloaded, Duration budget,
        long deadlineNanos) {
        Optional<ExcerptPermit.Held> held = permit.tryAcquire(remaining(deadlineNanos));
        if (held.isEmpty()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT,
                notFinished(checked, budget),
                "Another download_server_log call was building its excerpt (one at a time per MCP"
                    + " server) until the call's cliExportTimeout ran out",
                "Retry in a moment, or raise cliExportTimeout; large logs need a larger"
                    + " cliExportTimeout").withNode(checked.node()));
        }
        try (ExcerptPermit.Held taken = held.get()) {
            // D-13: the window is handed over, not kept here, so its bytes can be collected
            // while the decoded window is masked
            return LogExcerpt.build(LogWindowReader.read(downloaded.file(), downloaded.size()),
                checked.tailLines(), checked.contains(), checked.maxChars(),
                secrets::occurrences);
        } catch (IOException e) {
            throw new ToolErrorException(ToolError.of(ErrorCode.INTERNAL, "The downloaded "
                    + checked.kind().wireName() + " log of " + checked.node() + " could not be"
                    + " read (" + e.getClass().getSimpleName() + "); nothing was written",
                "The private temporary directory of the download was changed or its file system"
                    + " reported an error",
                "Retry; check the free space and permissions of the temporary directory"
                    + " (java.io.tmpdir)").withNode(checked.node()));
        }
    }

    /** Places the private copy in the target folder as {@code <group>-<node>-<log>-<time>}. */
    private LogFileWriter.Placed place(Checked checked, DownloadedLog downloaded,
        Instant downloadedAt) {
        NodeId node = checked.node();
        String baseName = node.group().value() + "-" + node.name() + "-"
            + checked.kind().wireName() + "-" + FILE_TIME.format(downloadedAt);
        try {
            return writer.place(checked.folder(), baseName, downloaded.file());
        } catch (IOException e) {
            throw new ToolErrorException(ToolError.of(ErrorCode.INTERNAL, "The log file could not"
                    + " be written to " + checked.folder().path() + " ("
                    + e.getClass().getSimpleName() + "); no file of this call remains there",
                "The target folder is not writable or its file system reported an error",
                "Check the folder's free space and permissions, or choose another target folder")
                .withNode(node));
        }
    }

    /** The hint of a 0-byte log (D-8, US2-4). */
    private static String emptyHint(ServerLogKind kind, LogPresence presence) {
        String hint = presence == LogPresence.UNKNOWN
            ? "The log is empty or does not exist on the server" : "The log is empty";
        return kind == ServerLogKind.WEBSERVICE
            ? hint + "; web service logging may be disabled" : hint;
    }

    /** The hint of a withheld excerpt: nodes, stores and states, never a value (FR-010). */
    private static String withheldHint(Readiness.NotReady notReady) {
        String pending = notReady.pending().stream()
            .map(p -> p.node() + " (" + p.store() + ": " + p.state() + ")")
            .collect(Collectors.joining(", "));
        return "The excerpt is withheld: the credentials of " + pending + " have not been read"
            + " yet, so they could not be masked; every excerpt is withheld until this source can"
            + " be read; the file is written";
    }

    /** "The <log> log download from <node> did not finish within cliExportTimeout (<n> s)". */
    private static String notFinished(Checked checked, Duration budget) {
        return "The " + checked.kind().wireName() + " log download from " + checked.node()
            + " did not finish within cliExportTimeout (" + Durations.human(budget) + ")";
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
