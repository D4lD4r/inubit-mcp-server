package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.application.DevelopmentGuard.Capability;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.DevelopmentPolicy;
import de.dadecker.inubit.mcp.domain.model.E2ePolicy;
import de.dadecker.inubit.mcp.domain.model.E2ePreview;
import de.dadecker.inubit.mcp.domain.model.E2eRun;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.LogEntry;
import de.dadecker.inubit.mcp.domain.model.LogQuery;
import de.dadecker.inubit.mcp.domain.model.LogType;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.domain.model.Severity;
import de.dadecker.inubit.mcp.domain.model.Target;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import de.dadecker.inubit.mcp.domain.port.E2ePort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The use case behind {@code run_e2e_test} (feature 004, US5, FR-022 – FR-024; research D-17,
 * D-18, D-25 H8, M9): sends a SOAP envelope from the workspace to an endpoint of a development
 * node and reports what INUBIT did with it.
 *
 * <ol>
 *   <li>inputs: a timeout of 1–120 s, a {@code SOAPAction} without quotes or control
 *       characters, a workflow name StartCLI quoting can carry; then the
 *       {@link DevelopmentGuard} ({@code e2eTests} FORBIDDEN is {@code E2E_FORBIDDEN});
 *   <li>the envelope path is confined to the workspace like the paths of
 *       {@code check_artifacts} (relative, no {@code ..}, a regular file whose real path is
 *       inside the workspace) and not below {@code .git}, {@code .meta} or {@code .reports}; an
 *       envelope with a {@code Password} element holding anything but a
 *       {@code ${secret:…}} placeholder is refused (a real password does not belong into the
 *       workspace); the endpoint path must stay below {@code e2e.soap.baseUrl};
 *   <li>{@code e2eTests: CONFIRM}: the first call returns a preview (endpoint, payload size,
 *       {@code SOAPAction}) and a code bound to the inputs and the payload hash;
 *   <li>{@code .tests/e2e} files older than 30 days are removed, the {@code PENDING} audit
 *       record is written (payload hash only), the envelope is sent unchanged with a fresh
 *       test id ({@code X-Inubit-Mcp-Test-Id});
 *   <li>the response goes to {@code .tests/e2e/<auditId>.response.xml}; an excerpt of at most
 *       2 KB only with {@code includeExcerpt};
 *   <li>correlation: the system and audit logs are searched for the test id in
 *       {@code [start − 5 s, end + 30 s]} (nothing waits for later entries; query_logs can look
 *       again); found entries give the process instances by their process ids. Otherwise the
 *       instances and system log entries of {@code workflow} in that window are returned,
 *       marked {@code TIME_WINDOW_UNCERTAIN}. A timeout keeps all of this; unreadable logs are
 *       a warning.
 * </ol>
 */
public final class E2eTestService {

    private static final Logger LOG = LoggerFactory.getLogger(E2eTestService.class);
    private static final Capability CAPABILITY = Capability.RUN_E2E_TEST;
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_.][A-Za-z0-9_.\\- ]{0,199}$");
    private static final Pattern ACTION = Pattern.compile("^[^\"\\p{Cntrl}]{0,500}$");
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9_-]{22}$");
    private static final Pattern PASSWORD = Pattern.compile(
        "<(?:[A-Za-z_][\\w.-]*:)?Password(?:\\s[^>]*)?>([^<]*)</");
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{secret:[^}]+}$");
    private static final Set<String> HIDDEN = Set.of(".git", ".meta", ".reports");
    private static final String RESPONSES = ".tests/e2e";
    private static final Duration KEEP = Duration.ofDays(30);
    private static final Duration BEFORE = Duration.ofSeconds(5);
    private static final Duration AFTER = Duration.ofSeconds(30);
    private static final int MAX_ITEMS = 20;
    private static final int EXCERPT_BYTES = 2048;
    private static final long MAX_ENVELOPE_BYTES = 10L << 20;
    private static final int MAX_AUDITED_INPUT = 500;

    /**
     * The collaborators.
     *
     * @param root              the workspace root (envelopes, {@code .tests/e2e})
     * @param backups           the backup directory, never a source of envelopes
     * @param hangingThresholds the hanging threshold of each node (process instances)
     * @param accounts          the INUBIT account of each node (audit)
     */
    public record Dependencies(Path root, Path backups, String profile, DevelopmentGuard guard,
        Function<NodeId, E2ePort> e2e, Function<NodeId, LogPort> logs,
        Function<NodeId, ProcessQueryPort> processes,
        Function<NodeId, Duration> hangingThresholds,
        Function<NodeId, ImportService.Account> accounts, WriteChallengeRegistry challenges,
        AuditPort audit, Clock clock, Supplier<UUID> ids) {

        public Dependencies {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(backups, "backups");
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(guard, "guard");
            Objects.requireNonNull(e2e, "e2e");
            Objects.requireNonNull(logs, "logs");
            Objects.requireNonNull(processes, "processes");
            Objects.requireNonNull(hangingThresholds, "hangingThresholds");
            Objects.requireNonNull(accounts, "accounts");
            Objects.requireNonNull(challenges, "challenges");
            Objects.requireNonNull(audit, "audit");
            Objects.requireNonNull(clock, "clock");
            Objects.requireNonNull(ids, "ids");
        }
    }

    /**
     * The arguments of {@code run_e2e_test}.
     *
     * @param envelope       the workspace-relative envelope file
     * @param path           the endpoint path below {@code e2e.soap.baseUrl}
     * @param workflow       the tested workflow, for the time-window fallback
     * @param timeoutSeconds 1–120
     */
    public record E2eRequest(String node, String envelope, String path,
        Optional<String> soapAction, Optional<String> workflow, int timeoutSeconds,
        boolean includeExcerpt, Optional<String> confirmationCode, Optional<String> mcpClient) {

        public E2eRequest {
            node = node == null ? "" : node;
            envelope = envelope == null ? "" : envelope;
            path = path == null ? "" : path;
            soapAction = soapAction == null ? Optional.empty() : soapAction;
            workflow = workflow == null ? Optional.empty() : workflow;
            confirmationCode = confirmationCode == null ? Optional.empty() : confirmationCode;
            mcpClient = mcpClient == null ? Optional.empty() : mcpClient;
        }
    }

    /** Either the preview ({@code CONFIRM}) or the run. */
    public sealed interface Response {

        /** Nothing was sent; the preview and its code. */
        record Challenge(E2ePreview preview) implements Response {
            public Challenge {
                Objects.requireNonNull(preview, "preview");
            }
        }

        /** The message was sent (or timed out): what came back and what INUBIT did. */
        record Completed(E2eRun run) implements Response {
            public Completed {
                Objects.requireNonNull(run, "run");
            }
        }
    }

    private final Dependencies d;

    public E2eTestService(Dependencies dependencies) {
        this.d = Objects.requireNonNull(dependencies, "dependencies");
    }

    /**
     * Runs one test (see the class description).
     *
     * @throws ToolErrorException every refusal before anything is sent (after its audit
     *     record); {@code UNREACHABLE} or {@code TLS_ERROR} if the endpoint cannot be reached
     *     (audited {@code FAILED})
     */
    public Response run(E2eRequest request) {
        Call call = new Call(request);
        DevelopmentPolicy policy;
        E2ePort port;
        byte[] payload;
        String endpoint;
        String inputs;
        try {
            validate(request);
            policy = d.guard().admit(request.node(), CAPABILITY);
            call.policy = policy;
            payload = envelope(request.envelope());
            call.payloadHash = "sha256:" + sha256(payload);
            requireNoPassword(payload);
            port = d.e2e().apply(policy.node());
            endpoint = port.endpoint(request.path());
            inputs = "sha256:" + sha256(String.join("\n", policy.node().value(),
                request.envelope(), request.path(), request.soapAction().orElse(""),
                request.workflow().orElse(""), String.valueOf(request.timeoutSeconds()),
                String.valueOf(request.includeExcerpt()), call.payloadHash)
                .getBytes(StandardCharsets.UTF_8));
            if (policy.e2eTests() == E2ePolicy.CONFIRM) {
                if (request.confirmationCode().isEmpty()) {
                    return challenge(call, policy, endpoint, payload.length, inputs);
                }
                d.challenges().redeem(request.confirmationCode().get(), CAPABILITY,
                    policy.node(), inputs);
            }
        } catch (ToolErrorException e) {
            if (call.refused) {
                throw e;
            }
            throw call.refuse(e.error());
        } catch (RuntimeException e) {
            if (call.refused) {
                throw e;
            }
            LOG.error("Unexpected failure while preparing run_e2e_test", e);
            throw call.refuse(ToolError.of(ErrorCode.INTERNAL, "Unexpected failure while"
                    + " preparing the test (" + e.getClass().getSimpleName() + "); nothing was"
                    + " sent", "An internal error of the INUBIT MCP server",
                "Retry; if it persists, check the MCP server log on stderr and report the"
                    + " problem"));
        }
        return send(call, policy.node(), port, endpoint, payload);
    }

    private Response challenge(Call call, DevelopmentPolicy policy, String endpoint,
        int payloadBytes, String inputs) {
        E2eRequest request = call.request;
        NodeId node = policy.node();
        WriteChallengeRegistry.Issued issued = d.challenges().issue(CAPABILITY, node, inputs,
            call.payloadHash, policy.confirmationTtl());
        Map<String, String> audited = call.inputs();
        audited.put(AuditRecord.ISSUED_CONFIRMATION_CODE, issued.code());
        append(call, d.ids().get(), AuditRecord.Step.PREVIEW, audited,
            AuditOutcome.CHALLENGE_ISSUED, "Preview of a SOAP test to " + request.path()
                + ", code valid until " + issued.expiresAt(),
            () -> d.challenges().discard(issued.code()));
        LOG.info("run_e2e_test on {}: CHALLENGE_ISSUED", node);
        return new Response.Challenge(new E2ePreview(node, endpoint, payloadBytes,
            request.soapAction(), issued.code(), issued.expiresAt(), "Nothing was sent. To"
                + " send " + payloadBytes + " bytes from " + request.envelope() + " to "
                + endpoint + ", show this preview to the user and, after their explicit"
                + " approval, call run_e2e_test again with the same inputs and"
                + " confirmationCode before " + issued.expiresAt() + "."));
    }

    private Response send(Call call, NodeId node, E2ePort port, String endpoint,
        byte[] payload) {
        E2eRequest request = call.request;
        List<String> warnings = new ArrayList<>();
        removeOldResponses(warnings);
        UUID auditId = d.ids().get();
        String testId = d.ids().get().toString();
        call.testId = testId;
        append(call, auditId, AuditRecord.Step.EXECUTE, call.inputs(), AuditOutcome.PENDING,
            "About to send a SOAP test to " + request.path(), () -> { });
        Instant start = d.clock().instant();
        E2ePort.Exchange exchange;
        try {
            exchange = port.post(new E2ePort.Message(request.path(), payload,
                request.soapAction(), testId, Duration.ofSeconds(request.timeoutSeconds())));
        } catch (ToolErrorException e) {
            append(call, auditId, AuditRecord.Step.EXECUTE, call.inputs(), AuditOutcome.FAILED,
                e.error().code() + ": " + e.error().message(), null);
            throw e;
        }
        Instant end = d.clock().instant();
        Optional<String> responseFile = Optional.empty();
        Optional<String> excerpt = Optional.empty();
        boolean truncated = false;
        if (exchange.status().isPresent()) {
            responseFile = Optional.of(write(auditId, exchange.body()));
            if (exchange.truncated()) {
                warnings.add("The response was longer than " + E2ePort.MAX_BODY_BYTES
                    + " bytes; the file holds the beginning");
            }
            if (request.includeExcerpt()) {
                excerpt = Optional.of(excerpt(exchange.body()));
                truncated = exchange.body().length > EXCERPT_BYTES;
            }
        }
        Correlated found = correlate(node, testId, request.workflow(), start, end, warnings);
        String outcome = exchange.timedOut() ? "timed out after "
            + request.timeoutSeconds() + " s" : "HTTP " + exchange.status().orElseThrow();
        append(call, auditId, AuditRecord.Step.EXECUTE, call.inputs(), AuditOutcome.EXECUTED,
            "SOAP test " + outcome + "; " + found.correlation() + ", "
                + found.processes().size() + " process(es)", null);
        LOG.info("run_e2e_test on {}: EXECUTED ({}, audit id {})", node, outcome, auditId);
        return new Response.Completed(new E2eRun(auditId, testId, endpoint, exchange.status(),
            exchange.duration().toMillis(), exchange.timedOut(), responseFile, excerpt,
            found.correlation(), found.processes(), found.errors(), found.logEntries(),
            truncated || found.truncated(), warnings));
    }

    // --- correlation ---------------------------------------------------------------------------

    private record Correlated(E2eRun.Correlation correlation, List<ProcessInstance> processes,
        List<LogEntry> errors, List<LogEntry> logEntries, boolean truncated) {
    }

    private Correlated correlate(NodeId node, String testId, Optional<String> workflow,
        Instant start, Instant end, List<String> warnings) {
        Instant since = start.minus(BEFORE);
        Instant until = end.plus(AFTER);
        Instant now = d.clock().instant();
        Duration threshold = d.hangingThresholds().apply(node);
        LogPort logs = d.logs().apply(node);
        ProcessQueryPort processes = d.processes().apply(node);
        List<LogEntry> found = new ArrayList<>();
        boolean truncated = false;
        for (LogType type : List.of(LogType.SYSTEM_LOG, LogType.AUDIT_LOG)) {
            LogQuery query = new LogQuery(type, Optional.of(since), Optional.of(until),
                Optional.empty(), Optional.empty(), Set.of(), Optional.of(testId), 0,
                MAX_ITEMS);
            Optional<Page<LogEntry>> page = logs(logs, query, warnings);
            if (page.isPresent()) {
                found.addAll(page.get().items());
                truncated |= page.get().truncated() || page.get().nextOffset().isPresent();
            }
        }
        if (!found.isEmpty()) {
            Set<String> ids = new LinkedHashSet<>();
            found.forEach(entry -> entry.processId().ifPresent(ids::add));
            List<ProcessInstance> instances = new ArrayList<>();
            for (String id : ids.stream().limit(MAX_ITEMS).toList()) {
                try {
                    instances.addAll(processes.findByProcessId(id, now, threshold).rows());
                } catch (ToolErrorException e) {
                    warnings.add("The process instances could not be read ("
                        + e.error().code() + ")");
                    break;
                }
            }
            truncated |= ids.size() > MAX_ITEMS || instances.size() > MAX_ITEMS;
            return new Correlated(E2eRun.Correlation.BY_TEST_ID, cut(instances),
                cut(errors(found)), cut(found), truncated);
        }
        if (workflow.isEmpty()) {
            warnings.add("No log entry names the test id; give workflow to see the instances"
                + " of the tested workflow in the time window of the test");
            return new Correlated(E2eRun.Correlation.TIME_WINDOW_UNCERTAIN, List.of(),
                List.of(), List.of(), truncated);
        }
        List<ProcessInstance> instances = List.of();
        try {
            Page<ProcessInstance> page = processes.find(new ProcessQuery(Set.of(), false,
                threshold, workflow, Optional.empty(), Optional.of(since), Optional.of(until), 0,
                MAX_ITEMS, now));
            instances = page.items();
            truncated |= page.truncated() || page.nextOffset().isPresent();
        } catch (ToolErrorException e) {
            warnings.add("The process instances could not be read (" + e.error().code() + ")");
        }
        List<LogEntry> entries = logs(logs, new LogQuery(LogType.SYSTEM_LOG, Optional.of(since),
            Optional.of(until), workflow, Optional.empty(), Set.of(), Optional.empty(), 0,
            MAX_ITEMS), warnings).map(Page::items).orElse(List.of());
        warnings.add("No log entry names the test id: the instances and log entries of "
            + workflow.get() + " in the time window of the test are shown; other traffic may"
            + " be among them");
        return new Correlated(E2eRun.Correlation.TIME_WINDOW_UNCERTAIN, cut(instances),
            cut(errors(entries)), cut(entries), truncated);
    }

    private static Optional<Page<LogEntry>> logs(LogPort logs, LogQuery query,
        List<String> warnings) {
        try {
            logs.validate(query);
        } catch (ToolErrorException e) {
            return Optional.empty();
        }
        try {
            return Optional.of(logs.query(query));
        } catch (ToolErrorException e) {
            warnings.add("The " + query.logType() + " could not be read (" + e.error().code()
                + ")");
            return Optional.empty();
        }
    }

    private static List<LogEntry> errors(List<LogEntry> entries) {
        return entries.stream().filter(entry -> entry.severity() == Severity.ERROR).toList();
    }

    private static <T> List<T> cut(List<T> items) {
        return items.size() <= MAX_ITEMS ? List.copyOf(items) : List.copyOf(items.subList(0,
            MAX_ITEMS));
    }

    // --- inputs and files ----------------------------------------------------------------------

    private static void validate(E2eRequest request) {
        if (request.timeoutSeconds() < 1 || request.timeoutSeconds() > 120) {
            throw invalid("timeoutSeconds must be between 1 and 120", "The test waits at most"
                + " two minutes for the answer", "Give a timeout of 1 to 120 seconds");
        }
        if (request.soapAction().filter(action -> !ACTION.matcher(action).matches())
            .isPresent()) {
            throw invalid("Invalid soapAction: no quotes or control characters, at most 500"
                + " characters", "It is sent as an HTTP header", "Give the plain action URI");
        }
        if (request.workflow().filter(name -> !NAME.matcher(name).matches()).isPresent()) {
            throw invalid("Invalid workflow name", "It must match " + NAME.pattern(),
                "Give the exact name of the tested workflow");
        }
        if (request.confirmationCode().filter(code -> !CODE.matcher(code).matches())
            .isPresent()) {
            throw invalid("Invalid confirmationCode", "A confirmation code has 22 URL-safe"
                + " Base64 chars", "Use the code of the preview exactly as returned");
        }
    }

    /**
     * The envelope bytes, read from a file confined to the workspace (research D-25 H8, review
     * I-A): a relative path without empty, {@code .} or {@code ..} segments, a regular file
     * whose REAL path (symbolic links resolved) lies inside the REAL workspace root and whose
     * first component there is none of {@code .git}, {@code .meta}, {@code .reports}, and that
     * is not in the backup directory (should the workspace contain it) — so
     * neither {@code ./.git/config} nor a link {@code samples/x.xml → ../.git/config} passes.
     */
    private byte[] envelope(String path) {
        String candidate = path.replace('\\', '/').strip();
        List<String> segments = List.of(candidate.split("/", -1));
        if (candidate.isEmpty() || candidate.startsWith("/") || candidate.matches("^[A-Za-z]:.*")
            || segments.stream().anyMatch(s -> s.isEmpty() || s.equals(".") || s.equals(".."))) {
            throw invalid("The envelope path is not a plain workspace-relative path",
                "Envelopes are read from the workspace only; empty, '.' and '..' segments are"
                    + " not allowed",
                "Put the envelope below the workspace (e.g. samples/order.xml) and give its"
                    + " relative path");
        }
        try {
            Path root = d.root().toRealPath();
            Path resolved = root.resolve(candidate);
            if (!Files.isRegularFile(resolved)) {
                throw invalid("The envelope file does not exist in the workspace",
                    "Only regular files inside the workspace are sent",
                    "Check the path (names are case-sensitive)");
            }
            Path real = resolved.toRealPath();
            Path backups = d.backups().toAbsolutePath().normalize();
            if (Files.exists(backups)) {
                backups = backups.toRealPath();
            }
            if (!real.startsWith(root) || real.equals(root) || real.startsWith(backups)
                || HIDDEN.contains(root.relativize(real).getName(0).toString())) {
                throw invalid("The envelope file leaves the workspace or lies below .git, .meta,"
                        + " .reports or the backups (symbolic links resolved)",
                    "Only workspace files outside the history and the metadata are sent",
                    "Put the envelope below the workspace, e.g. samples/order.xml");
            }
            if (Files.size(real) > MAX_ENVELOPE_BYTES) {
                throw invalid("The envelope is larger than " + MAX_ENVELOPE_BYTES + " bytes",
                    "A test message is small", "Use a smaller sample message");
            }
            return Files.readAllBytes(real);
        } catch (IOException e) {
            throw invalid("The envelope file cannot be read (" + e.getClass().getSimpleName()
                + ")", "The file system refused the read", "Check the file");
        }
    }

    /** Research D-25 H8: a WS-Security password in the workspace must be a placeholder. */
    private static void requireNoPassword(byte[] payload) {
        Matcher matcher = PASSWORD.matcher(new String(payload, StandardCharsets.UTF_8));
        while (matcher.find()) {
            String value = matcher.group(1).strip();
            if (!value.isEmpty() && !PLACEHOLDER.matcher(value).matches()) {
                throw invalid("The envelope contains a Password element with a value; nothing"
                        + " was sent",
                    "Passwords must not be stored in workspace files",
                    "Replace the value by a ${secret:…} placeholder or remove the security"
                        + " header (configure the endpoint's basic authentication instead)");
            }
        }
    }

    private String write(UUID auditId, byte[] body) {
        String name = RESPONSES + "/" + auditId + ".response.xml";
        Path file = d.root().resolve(name);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return name;
    }

    /** Research D-25 M9: response files older than 30 days are removed. */
    private void removeOldResponses(List<String> warnings) {
        Path directory = d.root().resolve(RESPONSES);
        if (!Files.isDirectory(directory)) {
            return;
        }
        Instant limit = d.clock().instant().minus(KEEP);
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (Files.getLastModifiedTime(file).toInstant().isBefore(limit)) {
                    Files.deleteIfExists(file);
                }
            }
        } catch (IOException e) {
            warnings.add("Old response files in " + RESPONSES + " could not be removed ("
                + e.getClass().getSimpleName() + ")");
        }
    }

    /** At most 2 KB of the body as UTF-8, cut at a character boundary. */
    private static String excerpt(byte[] body) {
        int end = Math.min(body.length, EXCERPT_BYTES);
        if (end < body.length) {
            while (end > 0 && (body[end] & 0xC0) == 0x80) {
                end--;
            }
        }
        return new String(body, 0, end, StandardCharsets.UTF_8);
    }

    private void append(Call call, UUID auditId, AuditRecord.Step step,
        Map<String, String> inputs, AuditOutcome outcome, String reason, Runnable onFailure) {
        try {
            d.audit().append(call.record(auditId, step, inputs, outcome, reason));
        } catch (RuntimeException e) {
            LOG.error("Audit record of run_e2e_test could not be written ({})",
                e.getClass().getSimpleName());
            if (onFailure == null) {
                return;
            }
            onFailure.run();
            call.refused = true;
            throw new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
                "The audit record could not be written; nothing was sent",
                "The audit directory is not writable, full, or not owned by this user",
                "Fix the audit directory (auditDirectory in the configuration), then retry"));
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ToolErrorException invalid(String message, String likelyCause,
        String nextStep) {
        return new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT, message
            + (message.endsWith("nothing was sent") ? "" : "; nothing was sent"), likelyCause,
            nextStep));
    }

    /** One call: its request, what is known so far and the refusal path. */
    private final class Call {

        final E2eRequest request;
        DevelopmentPolicy policy;
        String payloadHash;
        String testId;
        boolean refused;

        Call(E2eRequest request) {
            this.request = request;
        }

        /** The sanitized inputs, in a fixed order; never the payload. */
        Map<String, String> inputs() {
            Map<String, String> inputs = new LinkedHashMap<>();
            inputs.put("envelope", bounded(request.envelope()));
            inputs.put("path", bounded(request.path()));
            request.soapAction().ifPresent(action -> inputs.put("soapAction", bounded(action)));
            request.workflow().ifPresent(workflow -> inputs.put("workflow", bounded(workflow)));
            inputs.put("timeoutSeconds", String.valueOf(request.timeoutSeconds()));
            if (payloadHash != null) {
                inputs.put("payloadSha256", payloadHash);
            }
            if (testId != null) {
                inputs.put("testId", testId);
            }
            request.confirmationCode().ifPresent(code ->
                inputs.put(AuditRecord.CONFIRMATION_CODE, code));
            return inputs;
        }

        AuditRecord record(UUID auditId, AuditRecord.Step step, Map<String, String> inputs,
            AuditOutcome outcome, String reason) {
            Optional<NodeId> node = Optional.ofNullable(policy).map(DevelopmentPolicy::node);
            return new AuditRecord(auditId, d.clock().instant(), d.profile(),
                node.map(NodeId::value).orElse(bounded(request.node())), group(),
                CAPABILITY.toolName(), step, inputs, node.map(id -> d.accounts().apply(id)
                    .user()), outcome, Optional.of(bounded(reason)), request.mcpClient());
        }

        /** Audits {@code error} as {@code REFUSED}; discards a presented code. */
        ToolErrorException refuse(ToolError error) {
            refused = true;
            request.confirmationCode().ifPresent(d.challenges()::discard);
            boolean execute = request.confirmationCode().isPresent() || (policy != null
                && policy.e2eTests() == E2ePolicy.FREE);
            try {
                d.audit().append(record(d.ids().get(), execute ? AuditRecord.Step.EXECUTE
                    : AuditRecord.Step.PREVIEW, inputs(), AuditOutcome.REFUSED,
                    error.code() + ": " + error.message()));
            } catch (RuntimeException e) {
                LOG.error("Audit record of a refusal ({}) could not be written ({})",
                    error.code(), e.getClass().getSimpleName());
                return new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
                    "The audit record could not be written; the call was refused anyway ("
                        + error.code() + ": " + error.message() + ") and nothing was sent",
                    "The audit directory is not writable, full, or not owned by this user",
                    "Fix the audit directory (auditDirectory in the configuration), then"
                        + " retry"));
            }
            LOG.info("run_e2e_test on {}: REFUSED ({})", bounded(request.node()),
                error.code());
            return new ToolErrorException(error);
        }

        private Optional<String> group() {
            try {
                return Optional.of(switch (Target.parse(request.node())) {
                    case Target.Group group -> group.id().value();
                    case Target.Node node -> node.id().group().value();
                });
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }

        private static String bounded(String value) {
            return value.length() <= MAX_AUDITED_INPUT ? value
                : value.substring(0, MAX_AUDITED_INPUT);
        }
    }
}
