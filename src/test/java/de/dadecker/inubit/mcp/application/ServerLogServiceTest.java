package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ContentKind;
import de.dadecker.inubit.mcp.domain.model.CredentialState;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ExcerptLine;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.LogExcerpt;
import de.dadecker.inubit.mcp.domain.model.LogPresence;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ServerLogKind;
import de.dadecker.inubit.mcp.domain.model.ServerLogResult;
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
import de.dadecker.inubit.mcp.testing.FakeConfiguredSecrets;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.GitFreeTempDir;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Feature 021 T027 and T028 (contracts/mcp-tools-delta.md "Decision flow", "Output", FR-001,
 * FR-002, FR-007…FR-011, FR-013, FR-015, research D-7, D-8, D-11, D-13 … D-15, D-18):
 * {@link ServerLogService} — the input checks, the target, the opt-in, the folder rules, the
 * audit, the version gate with detection, the call deadline and the server's login credentials,
 * then the masking credentials, the excerpt permit, the excerpt, the placement and the result.
 * Fakes of the {@link ServerLogPort} (recording calls and the deadline, writing the log into a
 * private directory), the {@link GatewayFactory} (recording the {@code prepareCredentials} bound),
 * the {@link FakeConfiguredSecrets} and the audit; the real {@link VersionGate} over a scripted
 * detection, the real {@link ExcerptPermit} and {@link LogFileWriter}.
 */
class ServerLogServiceTest {

    private static final NodeId TEST = NodeId.parse("test/node1");
    private static final NodeId PROD = NodeId.parse("prod/node1");
    private static final NodeId NEW = NodeId.parse("test/node91");
    private static final NodeId AUTO = NodeId.parse("test/auto");
    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000027");
    private static final Duration EXPORT_TIMEOUT = Duration.ofSeconds(120);
    /** {@code ProfileSecrets.timeout()} as {@code Wiring} passes it. */
    private static final Duration PREPARE_BOUND = Duration.ofSeconds(30);
    /** The file name of a download that finished at {@link #NOW}. */
    private static final String NAME = "test-node1-trace-20261112-100000.log";
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
    /** The service's clock of {@code downloadedAt}; the fake download advances it. */
    private final MutableClock clock = new MutableClock(NOW);
    private FakeConfiguredSecrets secrets = FakeConfiguredSecrets.nothingConfigured();
    private final ExcerptPermit permit = new ExcerptPermit();
    private LogFileWriter writer = new LogFileWriter();
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

    @Test
    void aPortErrorWithoutNodeGetsTheServer() {
        port.failure = new ToolErrorException(ToolError.of(ErrorCode.CLI_UNAVAILABLE,
            "no client", "c", "s"));

        ToolError error = errorOf(() -> service().download(request(TEST.value(), "trace")));

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.node()).contains(TEST);
    }

    // --- excerpt, placement, result (steps 8 and 9) -------------------------------------------

    /** US1-1. */
    @Test
    void theResultNamesTheServerTheLogTheAbsoluteFileItsSizeTheTimeAndTheLast200Lines()
        throws IOException {
        port.content = lines(250).getBytes(StandardCharsets.UTF_8);

        ServerLogResult result = service().download(request(TEST.value(), "trace"));

        Path file = folder.resolve(NAME);
        assertThat(result.node()).isEqualTo(TEST);
        assertThat(result.log()).isEqualTo(ServerLogKind.TRACE);
        assertThat(result.inubitNode()).isEmpty();
        assertThat(result.file()).isEqualTo(file).isAbsolute();
        assertThat(result.sizeBytes()).isEqualTo(port.content.length).isEqualTo(Files.size(file));
        assertThat(result.downloadedAt()).isEqualTo(NOW);
        LogExcerpt excerpt = result.excerpt().orElseThrow();
        assertThat(excerpt.kind()).isEqualTo(ContentKind.TEXT);
        assertThat(excerpt.lines()).hasSize(200);
        assertThat(excerpt.lines().get(0).number()).isEqualTo(51);
        assertThat(excerpt.lines().get(0).text()).isEqualTo("line 51");
        assertThat(excerpt.lines().get(199).number()).isEqualTo(250);
        assertThat(result.hints()).isEmpty();
        assertThat(result.warnings()).containsExactly("The log file contains clear-text content");
        assertThat(port.closed).isEqualTo(1);
        assertThat(port.directory).doesNotExist();
    }

    @Test
    void theWebServiceLogNamesTheInubitNodeAndCarriesTheUnsupportedWarning() {
        ServerLogResult result = service().download(request(TEST.value(), "webservice"));

        assertThat(result.log()).isEqualTo(ServerLogKind.WEBSERVICE);
        assertThat(result.inubitNode()).contains("node1");
        assertThat(result.file().getFileName())
            .hasToString("test-node1-webservice-20261112-100000.log");
        assertThat(result.warnings()).containsExactly(
            "Read through an unsupported, undocumented INUBIT interface; diagnosis only",
            "The log file contains clear-text content");
    }

    @Test
    void theFinalRecordIsExecutedWithFileSizeAndInubitNode() {
        ServerLogResult result = service().download(request(TEST.value(), "webservice"));

        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
        assertThat(audit.records.get(1).details()).containsExactly(
            Map.entry("file", result.file().toString()),
            Map.entry("sizeBytes", result.sizeBytes()),
            Map.entry("inubitNode", "node1"));
    }

    @Test
    void anUnwritableFinalRecordOfASuccessIsAWarning() {
        audit.failAfter = 1;

        ServerLogResult result = service().download(request(TEST.value(), "trace"));

        assertThat(result.warnings()).last().asString()
            .startsWith("The final audit record could not be written (only the pending record "
                + ID + " exists)");
        assertThat(result.file()).exists();
    }

    @Test
    void theTimeIsTheSecondAtWhichTheDownloadFinishedInUtc() {
        port.elapsed = Duration.ofMillis(1_750);

        ServerLogResult result = service().download(request(TEST.value(), "trace"));

        assertThat(result.downloadedAt()).isEqualTo(Instant.parse("2026-11-12T10:00:01Z"));
        assertThat(result.file().getFileName()).hasToString("test-node1-trace-20261112-100001.log");
    }

    static List<Object[]> contents() {
        byte[] binary = new byte[512];
        for (int i = 0; i < binary.length; i++) {
            binary[i] = (byte) i;
        }
        return List.of(
            new Object[] {"UTF-8", "Größe: 5 €\nzweite Zeile\n".getBytes(StandardCharsets.UTF_8)},
            new Object[] {"Latin-1", "Größe: 5\nzweite Zeile\n"
                .getBytes(StandardCharsets.ISO_8859_1)},
            new Object[] {"binary", binary});
    }

    /** FR-008. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("contents")
    void theFileHoldsTheDownloadedBytesVerbatim(String name, byte[] content) throws IOException {
        port.content = content;

        ServerLogResult result = service().download(request(TEST.value(), "trace"));

        assertThat(Files.readAllBytes(result.file())).isEqualTo(content);
        assertThat(result.sizeBytes()).isEqualTo(content.length);
    }

    /** US1-5. */
    @Test
    void anExistingFileIsNotReplacedTheCallTakesTheNextName() throws IOException {
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(NAME), "earlier");

        ServerLogResult result = service().download(request(TEST.value(), "trace"));

        assertThat(result.file())
            .isEqualTo(folder.resolve("test-node1-trace-20261112-100000-2.log"));
        assertThat(folder.resolve(NAME)).hasContent("earlier");
    }

    static List<Object[]> emptyLogs() {
        return List.of(
            new Object[] {"trace", LogPresence.PRESENT, "The log is empty"},
            new Object[] {"webservice", LogPresence.PRESENT,
                "The log is empty; web service logging may be disabled"},
            new Object[] {"trace", LogPresence.UNKNOWN,
                "The log is empty or does not exist on the server"},
            new Object[] {"webservice", LogPresence.UNKNOWN, "The log is empty or does not exist"
                + " on the server; web service logging may be disabled"});
    }

    /** D-8, US2-4. */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("emptyLogs")
    void anEmptyLogIsWrittenWithAnEmptyExcerptAndItsHint(String log, LogPresence presence,
        String hint) {
        port.content = new byte[0];
        port.presence = presence;

        ServerLogResult result = service().download(request(TEST.value(), log));

        assertThat(result.file()).exists().isEmptyFile();
        assertThat(result.sizeBytes()).isZero();
        assertThat(result.excerpt()).hasValueSatisfying(excerpt -> {
            assertThat(excerpt.kind()).isEqualTo(ContentKind.EMPTY);
            assertThat(excerpt.lines()).isEmpty();
        });
        assertThat(result.hints()).containsExactly(hint);
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
    }

    @Test
    void containsWithoutMatchWritesTheFileWithAnEmptyExcerptAndItsHint() {
        ServerLogResult result = service().download(new ServerLogRequest(TEST.value(), "trace",
            folder.toString(), OptionalInt.empty(), Optional.of(NEEDLE), OptionalInt.empty(),
            Optional.empty()));

        assertThat(result.file()).exists();
        LogExcerpt excerpt = result.excerpt().orElseThrow();
        assertThat(excerpt.selection()).isEqualTo(LogExcerpt.Selection.CONTAINS);
        assertThat(excerpt.matchingLines()).hasValue(0);
        assertThat(excerpt.lines()).isEmpty();
        assertThat(excerpt.hints())
            .containsExactly("No line of the scanned window contains the search text");
        assertThat(result.toString()).doesNotContain(NEEDLE);
    }

    @Test
    void tailLinesAndMaxCharsReachTheExcerpt() {
        port.content = lines(10).getBytes(StandardCharsets.UTF_8);

        ServerLogResult result = service().download(new ServerLogRequest(TEST.value(), "trace",
            folder.toString(), OptionalInt.of(3), Optional.empty(), OptionalInt.of(14),
            Optional.empty()));

        LogExcerpt excerpt = result.excerpt().orElseThrow();
        assertThat(excerpt.lines()).extracting(ExcerptLine::text)
            .containsExactly("line 9", "line 10");
        assertThat(excerpt.truncated()).isPresent();
    }

    /** US1-3: a known password of another node is masked in the excerpt, not in the file. */
    @Test
    void aKnownPasswordOfAnotherNodeIsMaskedInTheExcerptButKeptInTheFile() throws IOException {
        secrets = FakeConfiguredSecrets.held("Pr0d-Node2-Passw0rd");
        port.content = "connect prod/node2 with Pr0d-Node2-Passw0rd done\n"
            .getBytes(StandardCharsets.UTF_8);

        ServerLogResult result = service().download(request(TEST.value(), "trace"));

        String line = result.excerpt().orElseThrow().lines().get(0).text();
        assertThat(line).doesNotContain("Pr0d-Node2-Passw0rd")
            .startsWith("connect prod/node2 with ").endsWith(" done");
        assertThat(Files.readString(result.file())).contains("Pr0d-Node2-Passw0rd");
    }

    /** US1-10, FR-010: fail closed for the excerpt only; no permit is taken. */
    @Test
    void credentialsNotYetReadWithholdTheExcerptButWriteTheFile() {
        secrets = FakeConfiguredSecrets.waiting("prod/node2", "credential store");
        port.content = "secret-looking line\n".getBytes(StandardCharsets.UTF_8);
        // a permit attempt would wait for the busy permit and end in TIMEOUT
        port.elapsed = EXPORT_TIMEOUT.minusMillis(100);

        ServerLogResult result;
        try (ExcerptPermit.Held busy = permit.tryAcquire(Duration.ZERO).orElseThrow()) {
            result = service().download(request(TEST.value(), "trace"));
        }

        assertThat(result.excerpt()).isEmpty();
        assertThat(result.hints()).containsExactly("The excerpt is withheld: the credentials of"
            + " prod/node2 (credential store: waiting) have not been read yet, so they could not"
            + " be masked; every excerpt is withheld until this source can be read; the file is"
            + " written");
        assertThat(result.toString()).doesNotContain("secret-looking line");
        assertThat(result.file()).exists();
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
    }

    @Test
    void theMaskingCredentialsAreBoundByThePrepareBoundWhileTheDeadlineLeavesMore() {
        service().download(request(TEST.value(), "trace"));

        assertThat(secrets.prepared()).containsExactly(PREPARE_BOUND);
    }

    @Test
    void theMaskingCredentialsAreBoundByTheRemainingDeadlineWhenLessIsLeft() {
        port.elapsed = EXPORT_TIMEOUT.minusSeconds(20);

        service().download(request(TEST.value(), "trace"));

        assertThat(secrets.prepared()).containsExactly(Duration.ofSeconds(20));
    }

    @Test
    void aDeadlinePassedDuringTheDownloadStillTakesAFreePermitAndPlacesTheFile() {
        port.elapsed = EXPORT_TIMEOUT.plusSeconds(1);

        ServerLogResult result = service().download(request(TEST.value(), "trace"));

        assertThat(secrets.prepared()).containsExactly(Duration.ZERO);
        assertThat(result.excerpt()).isPresent();
        assertThat(result.file()).exists();
    }

    /** D-13, FR-013. */
    @Test
    void aPermitHeldLongerThanTheRemainingDeadlineIsTimeoutAndLeavesNoFile() {
        port.elapsed = EXPORT_TIMEOUT.minusMillis(100);

        ToolError error;
        try (ExcerptPermit.Held busy = permit.tryAcquire(Duration.ZERO).orElseThrow()) {
            error = errorOf(() -> service().download(request(TEST.value(), "trace")));
        }

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.message()).isEqualTo("The trace log download from test/node1 did not"
            + " finish within cliExportTimeout (120 s)");
        assertThat(error.nextStep()).contains("cliExportTimeout");
        assertThat(error.node()).contains(TEST);
        assertNoFileInTheFolder();
        assertThat(port.directory).doesNotExist();
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.FAILED);
    }

    @Test
    void aPlacementFailureLeavesNoFileAndIsAuditedFailed() {
        writer = new LogFileWriter(new FailingForce());

        ToolError error = errorOf(() -> service().download(request(TEST.value(), "trace")));

        assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(error.message()).startsWith("The log file could not be written to " + folder);
        assertThat(error.node()).contains(TEST);
        assertNoFileInTheFolder();
        assertThat(port.directory).doesNotExist();
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.FAILED);
        assertThat(audit.records.get(1).details()).doesNotContainKey("file");
    }

    @Test
    void aDownloadThatCannotBeReadLeavesNoFileAndIsAuditedFailed() {
        port.reportedSize = port.content.length + 10L;

        ToolError error = errorOf(() -> service().download(request(TEST.value(), "trace")));

        assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(error.message()).startsWith("The downloaded trace log of test/node1 could not"
            + " be read");
        assertNoFileInTheFolder();
        assertThat(port.directory).doesNotExist();
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.FAILED);
    }

    /** FR-015: an Error while the excerpt is built still closes the record; nothing remains. */
    @Test
    void anErrorDuringTheExcerptIsAuditedFailedAndPropagates() {
        secrets.failOccurrences(new StackOverflowError());

        Throwable thrown = catchThrowable(() -> service().download(request(TEST.value(),
            "trace")));

        assertThat(thrown).isInstanceOf(StackOverflowError.class);
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.FAILED);
        assertThat(audit.records.get(1).reason()).contains("INTERNAL: StackOverflowError");
        assertNoFileInTheFolder();
        assertThat(port.directory).doesNotExist();
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
            node -> EXPORT_TIMEOUT, secrets, PREPARE_BOUND, permit, writer,
            new ServerLogAudit.Dependencies(audit, new MutableClock(NOW), "default", () -> ID),
            clock, () -> nanoTime.getAndAdd(tick));
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

    /** Runs a call whatever its outcome; a tool error is kept for the leak check. */
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

    /** {@code line 1\n} … {@code line <n>\n}. */
    private static String lines(int n) {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            text.append("line ").append(i).append('\n');
        }
        return text.toString();
    }

    /** No file of the call in the target folder; a folder the call created may stay. */
    private void assertNoFileInTheFolder() {
        if (Files.exists(folder)) {
            assertThat(folder).isEmptyDirectory();
        }
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

    /**
     * Records each download; writes {@code content} into a new private directory, lets
     * {@code elapsed} pass on the deadline seam and on the clock, and returns the log, whose close
     * deletes the directory and is counted. The web service log reports the INUBIT node
     * {@code node1}.
     */
    private final class FakePort implements ServerLogPort {

        final List<Call> calls = new ArrayList<>();
        ToolErrorException failure;
        byte[] content = "first line\nsecond line\n".getBytes(StandardCharsets.UTF_8);
        LogPresence presence = LogPresence.PRESENT;
        /** The size reported for the download; negative: the content's. */
        long reportedSize = -1;
        Duration elapsed = Duration.ZERO;
        Path directory;
        int closed;

        @Override
        public DownloadedLog download(ServerLogKind kind, InubitVersion version,
            long deadlineNanos) {
            calls.add(new Call(kind, version, deadlineNanos));
            if (failure != null) {
                throw failure;
            }
            try {
                directory = Files.createTempDirectory(root, "private");
                Path file = Files.write(directory.resolve(kind.fileName()), content);
                nanoTime.addAndGet(elapsed.toNanos());
                clock.advance(elapsed);
                return new DownloadedLog(file, reportedSize < 0 ? content.length : reportedSize,
                    kind == ServerLogKind.WEBSERVICE ? Optional.of("node1") : Optional.empty(),
                    presence, () -> {
                        closed++;
                        deleteTree(directory);
                    });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static void deleteTree(Path directory) {
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The system's file operations, except that forcing a file to the device fails. */
    private static final class FailingForce implements LogFileWriter.FileOps {

        private final LogFileWriter.FileOps system = LogFileWriter.FileOps.system();

        @Override
        public FileChannel createNew(Path file, FileAttribute<?>... attributes)
            throws IOException {
            return system.createNew(file, attributes);
        }

        @Override
        public long copy(Path source, FileChannel target) throws IOException {
            return system.copy(source, target);
        }

        @Override
        public void force(FileChannel channel) throws IOException {
            throw new IOException("No space left on device");
        }

        @Override
        public void link(Path link, Path existing) throws IOException {
            system.link(link, existing);
        }

        @Override
        public void move(Path source, Path target) throws IOException {
            system.move(source, target);
        }

        @Override
        public void delete(Path file) throws IOException {
            system.delete(file);
        }

        @Override
        public void createDirectory(Path directory, FileAttribute<?>... attributes)
            throws IOException {
            system.createDirectory(directory, attributes);
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
