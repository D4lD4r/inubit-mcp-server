package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.CredentialState;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.LogPresence;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ServerLogKind;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import de.dadecker.inubit.mcp.domain.port.DownloadedLog;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.domain.port.ServerLogPort;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.GitFreeTempDir;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Feature 021 T027 (contracts/mcp-tools-delta.md "Decision flow" steps 1–7, FR-001, FR-002,
 * FR-011, FR-013, research D-7 step 1, D-14, D-15, D-18): {@link ServerLogService} up to the
 * download — the input checks, the target, the opt-in, the folder rules, the audit, the version
 * gate with detection, the call deadline and the server's login credentials. Fakes of the
 * {@link ServerLogPort} (recording calls and the deadline), the {@link GatewayFactory} (recording
 * the {@code prepareCredentials} bound) and the audit; the real {@link VersionGate} over a
 * scripted detection. Nothing is downloaded.
 */
class ServerLogServiceTest {

    private static final NodeId TEST = NodeId.parse("test/node1");
    private static final NodeId PROD = NodeId.parse("prod/node1");
    private static final NodeId NEW = NodeId.parse("test/node91");
    private static final NodeId AUTO = NodeId.parse("test/auto");
    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000027");
    private static final Duration EXPORT_TIMEOUT = Duration.ofSeconds(120);
    private static final long START = 5_000_000_000L;
    private static final InubitVersion V8_1_17 = InubitVersion.parse("8.1.17").orElseThrow();
    /** A {@code contains} text that must never appear in a message, record or log line. */
    private static final String NEEDLE = "Needle-Hunter2-Contains";

    /** Both server logs supported on 8.1; every other version unsupported. */
    private static final CapabilityMatrix MATRIX = new CapabilityMatrix(List.of(
        new CapabilityMatrix.Entry(new VersionRange(8, 1, 8, 1),
            Map.of(Capability.TRACE_LOG_READ, SupportState.SUPPORTED,
                Capability.WEBSERVICE_LOG_READ, SupportState.SUPPORTED))),
        Map.of());

    @TempDir(factory = GitFreeTempDir.class)
    Path tempDir;

    private Path root;
    private Path home;
    private Path workspace;
    private Path configDir;
    private Path folder;

    private final Map<NodeId, VersionSetting> settings = Map.of(TEST, VersionSetting.AUTO,
        PROD, VersionSetting.AUTO, NEW, VersionSetting.AUTO, AUTO, VersionSetting.AUTO);
    private final NodeVersions versions = new NodeVersions(new MutableClock(NOW), settings::get,
        node -> Duration.ofMinutes(10));
    private final FakeVersionDetection detection = new FakeVersionDetection(versions)
        .answer(TEST, "8.1.17").answer(PROD, "8.1.17").answer(NEW, "9.1.0");
    private final RecordingAudit audit = new RecordingAudit();
    private final FakePort port = new FakePort();
    private final FakeGateways gateways = new FakeGateways();
    /** The seam's time; each reading advances it by {@code tick}. */
    private final AtomicLong nanoTime = new AtomicLong(START);
    private long tick;
    private final List<ToolError> errors = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        root = tempDir.toRealPath();
        home = Files.createDirectories(root.resolve("home"));
        workspace = Files.createDirectories(root.resolve("workspace"));
        configDir = Files.createDirectories(root.resolve("config"));
        folder = root.resolve("logs");
    }

    @AfterEach
    void noLeak() {
        errors.forEach(error -> assertThat(error.toString()).doesNotContain(NEEDLE));
        audit.records.forEach(record -> assertThat(record.toString()).doesNotContain(NEEDLE));
    }

    // --- input checks (step 1) ----------------------------------------------------------------

    static List<Object[]> invalidInputs() {
        return List.of(
            new Object[] {"syslog", "", OptionalInt.empty(), Optional.empty(), OptionalInt.empty(),
                "Invalid log: expected trace or webservice"},
            new Object[] {"TRACE", "", OptionalInt.empty(), Optional.empty(), OptionalInt.empty(),
                "Invalid log: expected trace or webservice"},
            new Object[] {"trace", "-", OptionalInt.empty(), Optional.empty(), OptionalInt.empty(),
                "Invalid targetFolder: expected 1–4096 characters"},
            new Object[] {"trace", "+", OptionalInt.empty(), Optional.empty(), OptionalInt.empty(),
                "Invalid targetFolder: expected 1–4096 characters"},
            new Object[] {"trace", null, OptionalInt.of(0), Optional.empty(), OptionalInt.empty(),
                "Invalid tailLines 0 (1–2000)"},
            new Object[] {"trace", null, OptionalInt.of(2001), Optional.empty(),
                OptionalInt.empty(), "Invalid tailLines 2001 (1–2000)"},
            new Object[] {"trace", null, OptionalInt.empty(), Optional.of(""), OptionalInt.empty(),
                "Invalid contains: expected 1–200 characters without control characters"},
            new Object[] {"trace", null, OptionalInt.empty(), Optional.of(NEEDLE + "x".repeat(200)),
                OptionalInt.empty(),
                "Invalid contains: expected 1–200 characters without control characters"},
            new Object[] {"trace", null, OptionalInt.empty(), Optional.of(NEEDLE + "\u0007"),
                OptionalInt.empty(),
                "Invalid contains: expected 1–200 characters without control characters"},
            new Object[] {"trace", null, OptionalInt.empty(), Optional.of(NEEDLE + "\u0085"),
                OptionalInt.empty(),
                "Invalid contains: expected 1–200 characters without control characters"},
            new Object[] {"trace", null, OptionalInt.empty(), Optional.empty(), OptionalInt.of(0),
                "Invalid maxChars 0 (1–100000)"},
            new Object[] {"trace", null, OptionalInt.empty(), Optional.empty(),
                OptionalInt.of(100_001), "Invalid maxChars 100001 (1–100000)"});
    }

    @ParameterizedTest
    @MethodSource("invalidInputs")
    void eachInvalidInputIsRefusedBeforeAnythingIsSentOrCreated(String log, String targetFolder,
        OptionalInt tailLines, Optional<String> contains, OptionalInt maxChars, String message) {
        String folderInput = switch (targetFolder == null ? "ok" : targetFolder) {
            case "-" -> "";
            case "+" -> folder + "/" + "x".repeat(4096);
            default -> folder.toString();
        };

        ToolError error = errorOf(() -> service().download(new ServerLogRequest(TEST.value(), log,
            folderInput, tailLines, contains, maxChars, Optional.empty())));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).isEqualTo(message);
        assertNothingSentOrCreated();
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
        assertThat(audit.records.get(0).reason()).contains("INVALID_INPUT: " + message);
    }

    @Test
    void theLimitsThemselvesAreAccepted() {
        ServerLogRequest request = new ServerLogRequest(TEST.value(), "trace", folder.toString(),
            OptionalInt.of(2000), Optional.of(NEEDLE + "y".repeat(200 - NEEDLE.length())),
            OptionalInt.of(100_000), Optional.empty());

        attempt(request);

        assertThat(port.calls).hasSize(1);
    }

    // --- target (step 2) ----------------------------------------------------------------------

    @Test
    void aGroupIdIsRefusedWithTheSingleServerText() {
        ToolError error = errorOf(() -> service().download(request("test", "trace")));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).isEqualTo("'test' is the id of one group; this action needs"
            + " the id of exactly one node (<group>/<node>)");
        assertNothingSentOrCreated();
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
    }

    @Test
    void anUnknownServerIsTargetUnknown() {
        ToolError error = errorOf(() -> service().download(request("test/nope", "trace")));

        assertThat(error.code()).isEqualTo(ErrorCode.TARGET_UNKNOWN);
        assertNothingSentOrCreated();
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
    }

    @Test
    void aMalformedTargetIsInvalidInput() {
        ToolError error = errorOf(() -> service().download(request("Test/Node 1", "trace")));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertNothingSentOrCreated();
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
    }

    // --- opt-in (step 3, US2-3 on service level) ----------------------------------------------

    @Test
    void theWebServiceLogWithoutOptInIsNotConfiguredBeforeDetectionOrAnyRequest() {
        ToolError error = errorOf(() -> service().download(request(PROD.value(), "webservice")));

        assertThat(error.code()).isEqualTo(ErrorCode.NOT_CONFIGURED);
        assertThat(error.message()).isEqualTo("download_server_log for the web service log is not"
            + " enabled for group prod: unsupported.serverLogRead is not set");
        assertThat(error.nextStep()).isEqualTo("Set unsupported.serverLogRead: true on group prod"
            + " and restart the MCP client (unsupported INUBIT functionality, see docs/setup.md"
            + " \"Unsupported functions\")");
        assertThat(error.node()).contains(PROD);
        assertNothingSentOrCreated();
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
        assertThat(audit.records.get(0).node()).isEqualTo(PROD.value());
    }

    @Test
    void theTraceLogNeedsNoOptIn() {
        attempt(request(PROD.value(), "trace"));

        assertThat(port.calls).extracting(Call::kind).containsExactly(ServerLogKind.TRACE);
    }

    @Test
    void theWebServiceLogWithOptInIsDownloaded() {
        attempt(request(TEST.value(), "webservice"));

        assertThat(port.calls).extracting(Call::kind).containsExactly(ServerLogKind.WEBSERVICE);
    }

    // --- folder rules (step 4) ----------------------------------------------------------------

    @Test
    void everyFolderRuleRefusesWithInvalidInputWithoutTalkingAboutArchives() throws IOException {
        Path repo = Files.createDirectories(root.resolve("repo"));
        Files.createDirectories(repo.resolve(".git"));
        Map<String, String> refused = new java.util.LinkedHashMap<>();
        refused.put("logs", "is not an absolute path");
        refused.put(root + "/a/../logs", "has a '.' or '..' element");
        refused.put(workspace.resolve("logs").toString(), "inside one of the server's directories");
        refused.put(configDir.resolve("logs").toString(), "inside one of the server's directories");
        refused.put(repo.resolve("logs").toString(), "inside a git working tree");

        for (Map.Entry<String, String> entry : refused.entrySet()) {
            ToolError error = errorOf(() -> service().download(new ServerLogRequest(TEST.value(),
                "trace", entry.getKey(), OptionalInt.empty(), Optional.empty(),
                OptionalInt.empty(), Optional.empty())));

            assertThat(error.code()).as(entry.getKey()).isEqualTo(ErrorCode.INVALID_INPUT);
            assertThat(error.message()).as(entry.getKey()).contains(entry.getValue());
            assertThat(error.node()).contains(TEST);
            assertThat(error.toString()).doesNotContainIgnoringCase("archive");
        }
        assertThat(repo.resolve("logs")).doesNotExist();
        assertThat(workspace.resolve("logs")).doesNotExist();
        assertNothingSentOrCreated();
        assertThat(outcomes()).containsOnly(AuditOutcome.REFUSED).hasSize(refused.size());
    }

    @Test
    void aNetworkPathIsRefusedOnWindows() {
        TargetFolderRules windows = new TargetFolderRules(home, workspace, root.resolve("audit"),
            List.of(configDir), Map.of(), letter -> letter == 'Z', true);

        for (String network : List.of("\\\\server\\share\\logs", "Z:\\logs")) {
            ToolError error = errorOf(() -> service(windows, node -> true).download(
                request(TEST.value(), "trace", network)));

            assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
            assertThat(error.message()).contains("is a network path");
            assertThat(error.likelyCause()).isEqualTo("Log files with clear-text content must"
                + " stay on a local disk");
        }
        assertNothingSentOrCreated();
    }

    // --- audit PENDING (step 5) ---------------------------------------------------------------

    @Test
    void anUnwritablePendingRecordIsInternalAndNothingIsSent() {
        audit.failing = true;

        ToolError error = errorOf(() -> service().download(request(TEST.value(), "trace")));

        assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(error.message()).isEqualTo("The audit record could not be written; nothing"
            + " was downloaded");
        assertNothingSentOrCreated();
    }

    // --- version gate (step 6) ----------------------------------------------------------------

    @Test
    void anUnsupportedVersionIsRefusedAndClosesThePendingRecordWithRefused() {
        ToolError error = errorOf(() -> service().download(request(NEW.value(), "trace")));

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.message()).startsWith("download_server_log: ");
        assertThat(gateways.calls).isEmpty();
        assertThat(port.calls).isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.REFUSED);
        assertThat(audit.records.get(1).reason()).hasValueSatisfying(reason -> assertThat(reason)
            .startsWith("UNSUPPORTED_VERSION: download_server_log: "));
    }

    /** US3-3 and the edge case "maintenance mode, credentials not available, TLS failure". */
    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"UNREACHABLE", "TLS_ERROR", "AUTH_FAILED",
        "TIMEOUT", "MAINTENANCE_MODE", "SECRET_UNRESOLVED"})
    void anUndetectableVersionPassesTheDetectionErrorThroughAndIsAuditedFailed(ErrorCode code) {
        detection.fail(AUTO, code);

        ToolError error = errorOf(() -> service().download(request(AUTO.value(), "trace")));

        assertThat(error.code()).isEqualTo(code);
        assertThat(error.message()).isEqualTo("detection failed");
        assertThat(detection.requests()).containsExactly(AUTO);
        assertThat(gateways.calls).isEmpty();
        assertThat(port.calls).isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.FAILED);
        assertThat(audit.records.get(1).reason()).contains(code + ": detection failed");
    }

    // --- call deadline and credentials (step 7) -----------------------------------------------

    @Test
    void thePortGetsTheDeadlineOfTheSeamsTimePlusCliExportTimeout() {
        attempt(request(TEST.value(), "trace"));

        assertThat(port.calls).containsExactly(new Call(ServerLogKind.TRACE, V8_1_17,
            START + EXPORT_TIMEOUT.toNanos()));
    }

    @Test
    void credentialsArePreparedWithinTheRemainingDeadlineBeforeThePortIsTaken() {
        attempt(request(TEST.value(), "trace"));

        assertThat(gateways.calls).containsExactly("prepareCredentials " + TEST,
            "serverLogs " + TEST);
        assertThat(gateways.bounds).containsExactly(EXPORT_TIMEOUT);
    }

    @Test
    void theCredentialBoundIsWhatIsLeftOfTheDeadlineAndNeverNegative() {
        tick = EXPORT_TIMEOUT.toNanos() + 1;

        attempt(request(TEST.value(), "trace"));

        assertThat(gateways.bounds).containsExactly(Duration.ZERO);
    }

    @Test
    void credentialsNotReadInTimeAreTimeoutNamingCliExportTimeoutAndThePortIsNotCalled() {
        gateways.state = CredentialState.PENDING;

        ToolError error = errorOf(() -> service().download(request(TEST.value(), "trace")));

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.message()).isEqualTo("The trace log download from test/node1 did not"
            + " finish within cliExportTimeout (120 s): the login credentials of test/node1 were"
            + " not read in time");
        assertThat(error.nextStep()).contains("cliExportTimeout");
        assertThat(error.node()).contains(TEST);
        assertThat(gateways.calls).containsExactly("prepareCredentials " + TEST);
        assertThat(port.calls).isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.FAILED);
        assertThat(audit.records.get(1).reason()).hasValueSatisfying(reason -> assertThat(reason)
            .startsWith("TIMEOUT: The trace log download from test/node1"));
    }

    @Test
    void aFailedCredentialFetchPassesThroughAndThePortIsNotCalled() {
        ToolError fetch = ToolError.of(ErrorCode.AUTH_FAILED, "The credentials of test/node1"
            + " could not be read", "cause", "next").withNode(TEST);
        gateways.failure = new ToolErrorException(fetch);

        ToolError error = errorOf(() -> service().download(request(TEST.value(), "trace")));

        assertThat(error).isEqualTo(fetch);
        assertThat(gateways.calls).containsExactly("prepareCredentials " + TEST);
        assertThat(port.calls).isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.FAILED);
        assertThat(audit.records.get(1).reason()).contains("AUTH_FAILED: The credentials of"
            + " test/node1 could not be read");
    }

    @Test
    void aFailedDownloadPassesThroughAndIsAuditedFailed() {
        ToolError timeout = ToolError.of(ErrorCode.TIMEOUT, "The trace log download from"
            + " test/node1 did not finish within cliExportTimeout (120 s)", "cause",
            "raise cliExportTimeout").withNode(TEST);
        port.failure = new ToolErrorException(timeout);

        ToolError error = errorOf(() -> service().download(request(TEST.value(), "trace")));

        assertThat(error).isEqualTo(timeout);
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.FAILED);
    }

    @Test
    void anUnwritableFinalRecordIsNamedInTheError() {
        port.failure = new ToolErrorException(ToolError.of(ErrorCode.UNREACHABLE, "down", "c",
            "s").withNode(TEST));
        audit.failAfter = 1;

        ToolError error = errorOf(() -> service().download(request(TEST.value(), "trace")));

        assertThat(error.code()).isEqualTo(ErrorCode.UNREACHABLE);
        assertThat(error.message()).startsWith("down. The final audit record could not be"
            + " written (only the pending record " + ID + " exists)");
    }

    @Test
    void theDownloadedLogIsClosedWhateverFollows() {
        attempt(request(TEST.value(), "trace"));

        assertThat(port.closed).isEqualTo(1);
        assertThat(outcomes()).startsWith(AuditOutcome.PENDING).hasSize(2);
    }

    // --- leak check ---------------------------------------------------------------------------

    @Test
    void theContainsTextIsNeverInTheRequestsTextNorInAnyRecord() {
        ServerLogRequest request = new ServerLogRequest(TEST.value(), "trace", folder.toString(),
            OptionalInt.empty(), Optional.of(NEEDLE), OptionalInt.empty(), Optional.of("c/1"));

        assertThat(request.toString()).doesNotContain(NEEDLE).contains("containsGiven=true");
        attempt(request);
        gateways.state = CredentialState.PENDING;
        attempt(request);
        attempt(new ServerLogRequest(PROD.value(), "webservice", folder.toString(),
            OptionalInt.empty(), Optional.of(NEEDLE), OptionalInt.empty(), Optional.empty()));

        assertThat(audit.records).isNotEmpty().allSatisfy(record -> assertThat(
            record.inputs()).containsEntry("containsGiven", "true"));
    }

    // --- helpers ------------------------------------------------------------------------------

    private ServerLogService service() {
        return service(rules(), node -> !node.group().value().equals("prod"));
    }

    private ServerLogService service(TargetFolderRules rules, Predicate<NodeId> optedIn) {
        return new ServerLogService(new TargetResolver(List.of(TEST, PROD, NEW, AUTO)), optedIn,
            node -> Optional.of("jdoe"), rules,
            FakeVersionDetection.gateWithoutClients(versions, detection, MATRIX), gateways,
            node -> EXPORT_TIMEOUT, new ServerLogAudit.Dependencies(audit, new MutableClock(NOW),
                "default", () -> ID), () -> nanoTime.getAndAdd(tick));
    }

    private TargetFolderRules rules() {
        return new TargetFolderRules(home, workspace, root.resolve("audit"), List.of(configDir),
            Map.of(), letter -> false, false);
    }

    private ServerLogRequest request(String target, String log) {
        return request(target, log, folder.toString());
    }

    private static ServerLogRequest request(String target, String log, String targetFolder) {
        return new ServerLogRequest(target, log, targetFolder, OptionalInt.empty(),
            Optional.empty(), OptionalInt.empty(), Optional.empty());
    }

    /**
     * Runs a call through the download; what follows the download (excerpt, placement, result)
     * is not part of these tests.
     */
    private void attempt(ServerLogRequest request) {
        Throwable thrown = catchThrowable(() -> service().download(request));
        if (thrown instanceof ToolErrorException e) {
            errors.add(e.error());
        }
    }

    private ToolError errorOf(Runnable call) {
        ToolErrorException thrown = catchThrowableOfType(ToolErrorException.class, call::run);
        assertThat(thrown).isNotNull();
        errors.add(thrown.error());
        return thrown.error();
    }

    private void assertNothingSentOrCreated() {
        assertThat(detection.requests()).isEmpty();
        assertThat(gateways.calls).isEmpty();
        assertThat(port.calls).isEmpty();
        assertThat(folder).doesNotExist();
    }

    private List<AuditOutcome> outcomes() {
        return audit.records.stream().map(AuditRecord::outcome).toList();
    }

    private record Call(ServerLogKind kind, InubitVersion version, long deadlineNanos) {
    }

    /** Records each download and returns a log whose private directory counts its closes. */
    private final class FakePort implements ServerLogPort {

        final List<Call> calls = new ArrayList<>();
        ToolErrorException failure;
        int closed;

        @Override
        public DownloadedLog download(ServerLogKind kind, InubitVersion version,
            long deadlineNanos) {
            calls.add(new Call(kind, version, deadlineNanos));
            if (failure != null) {
                throw failure;
            }
            return new DownloadedLog(root.resolve("private").resolve(kind.fileName()), 0,
                Optional.empty(), LogPresence.PRESENT, () -> closed++);
        }
    }

    /**
     * Only {@code prepareCredentials} and {@code serverLogs}, recorded in call order; every other
     * port is an assertion error.
     */
    private final class FakeGateways implements GatewayFactory {

        final List<String> calls = new ArrayList<>();
        final List<Duration> bounds = new ArrayList<>();
        CredentialState state = CredentialState.READY;
        ToolErrorException failure;

        @Override
        public CredentialState prepareCredentials(NodeId server, Duration bound) {
            calls.add("prepareCredentials " + server);
            bounds.add(bound);
            if (failure != null) {
                throw failure;
            }
            return state;
        }

        @Override
        public ServerLogPort serverLogs(NodeId server) {
            calls.add("serverLogs " + server);
            return port;
        }

        @Override
        public Gateway forServer(NodeId server) {
            throw new AssertionError("not expected");
        }

        @Override
        public InubitVersion detect(NodeId server) {
            throw new AssertionError("not expected");
        }

        @Override
        public MonitoringPort monitoring(NodeId server) {
            throw new AssertionError("not expected");
        }

        @Override
        public ProcessQueryPort processes(NodeId server) {
            throw new AssertionError("not expected");
        }

        @Override
        public LogPort logs(NodeId server) {
            throw new AssertionError("not expected");
        }

        @Override
        public InventoryPort inventory(NodeId server) {
            throw new AssertionError("not expected");
        }

        @Override
        public ProcessControlPort processControl(NodeId server) {
            throw new AssertionError("not expected");
        }

        @Override
        public Optional<Gateway> knownGateway(NodeId server) {
            throw new AssertionError("not expected");
        }
    }

    /**
     * Records every audit record; fails every append while {@code failing}, and every append
     * once {@code failAfter} records are written.
     */
    private static final class RecordingAudit implements AuditPort {

        final List<AuditRecord> records = new ArrayList<>();
        boolean failing;
        int failAfter = Integer.MAX_VALUE;

        @Override
        public void append(AuditRecord record) {
            if (failing || records.size() >= failAfter) {
                throw new UncheckedIOException(new IOException("disk full"));
            }
            records.add(record);
        }
    }
}
