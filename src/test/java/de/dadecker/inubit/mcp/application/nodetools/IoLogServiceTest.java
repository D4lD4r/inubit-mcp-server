package de.dadecker.inubit.mcp.application.nodetools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.application.NodeVersions;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.domain.model.BinarySignature;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.CredentialState;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.QueueEntryBounds;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.node.IoDirection;
import de.dadecker.inubit.mcp.domain.model.node.IoLogEntry;
import de.dadecker.inubit.mcp.domain.model.node.IoLogListing;
import de.dadecker.inubit.mcp.domain.model.node.IoLogResult;
import de.dadecker.inubit.mcp.domain.model.node.IoMessageRef;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.IoLogPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.GitFreeTempDir;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/**
 * Feature 024 T028 and T029 (FR-020 … FR-023, research D-8, D-9, D-22;
 * contracts/mcp-tools-delta.md "read_io_log" and "Common rules"): the listing of
 * {@link IoLogService} — the input checks, the one server, the opt-in, the version gate with
 * detection, the call deadline ({@code cliTimeout}) and the server's login credentials, the port's
 * listing, the {@code module} filter, the 500-entry cap with {@code total}, {@code matching} and
 * {@code truncated}, the hints, and the one INFO line — and the message read: the deadline
 * {@code cliExportTimeout}, a ref the listing does not have, the content shaped by the 020 rules
 * (masked before the cut, UTF-8 or ISO-8859-1, binary, empty, over 10 MiB) and the read closed on
 * every path. Fakes of the {@link IoLogPort} (recording calls and the deadline; a read answers a
 * file in a temporary directory) and the {@link GatewayFactory} (recording the
 * {@code prepareCredentials} bound); the real
 * {@link de.dadecker.inubit.mcp.application.VersionGate} over a scripted detection; the real
 * {@link SecretScrubber} holding the configured password of {@code prod/node1}, another server
 * than the one read.
 *
 * <p>The port is the adapter's contract: it returns every entry of the requested process parsed
 * with {@link IoLogEntry#parse} (an entry of another process is already its
 * {@code UNEXPECTED_RESPONSE}); a module part that is not {@code <name>(<id>)} arrives with its raw
 * module part as {@code moduleName} and no {@code moduleId}.
 */
class IoLogServiceTest {

    private static final NodeId TEST = NodeId.parse("test/node1");
    private static final NodeId PROD = NodeId.parse("prod/node1");
    private static final NodeId NEW = NodeId.parse("test/node91");
    private static final NodeId AUTO = NodeId.parse("test/auto");
    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
    private static final Duration CLI_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration EXPORT_TIMEOUT = Duration.ofSeconds(120);
    private static final long START = 7_000_000_000L;
    private static final InubitVersion V8_1_17 = InubitVersion.parse("8.1.17").orElseThrow();
    private static final String WORKFLOW = "Orders-Inbound";
    private static final String PROCESS = "4711";
    private static final String WARNING =
        "Read through an unsupported, undocumented INUBIT interface; diagnosis only";
    private static final String EMPTY_HINT = "No logged message for this run: input/output"
        + " logging may be off for the process or its modules, the messages may already be"
        + " removed, or the run may have executed on another cluster node";
    private static final String EMPTY_MESSAGE_HINT = "The message is empty or no longer available";
    private static final String BINARY_HINT = "Binary content is not returned";
    private static final String RAW_MODULE_HINT =
        "Some entries have an unexpected form; they are listed with their raw module part";

    private static final IoLogEntry CONNECTOR_IN = entry("Order Connector(Module-0003)@@@Input.1");
    private static final IoLogEntry CONNECTOR_OUT =
        entry("Order Connector(Module-0003)@@@Output.1");
    private static final IoLogEntry AUDIT_IN = entry("Audit Logger(Module-0007)@@@Input.1");
    private static final IoLogEntry AUDIT_OUT = entry("Audit Logger(Module-0007)@@@Output.1");
    private static final IoLogListing LISTING = new IoLogListing("node1",
        List.of(CONNECTOR_IN, CONNECTOR_OUT, AUDIT_IN, AUDIT_OUT));

    /** {@code io_log_read} supported on 8.1 (the test matrix); every other one not. */
    private static final CapabilityMatrix MATRIX = new CapabilityMatrix(List.of(
        new CapabilityMatrix.Entry(new VersionRange(8, 1, 8, 1),
            Map.of(Capability.IO_LOG_READ, SupportState.SUPPORTED))),
        Map.of());

    private final Map<NodeId, VersionSetting> settings = Map.of(TEST, VersionSetting.AUTO,
        PROD, VersionSetting.AUTO, NEW, VersionSetting.AUTO, AUTO, VersionSetting.AUTO);
    private final NodeVersions versions = new NodeVersions(new MutableClock(NOW), settings::get,
        node -> Duration.ofMinutes(10));
    private final FakeVersionDetection detection = new FakeVersionDetection(versions)
        .answer(TEST, "8.1.17").answer(PROD, "8.1.17").answer(NEW, "9.1.0");
    private final FakePort port = new FakePort();
    private final FakeGateways gateways = new FakeGateways();
    /** The seam's time; each reading advances it by {@code tick}. */
    private final AtomicLong nanoTime = new AtomicLong(START);
    private long tick;
    private final List<ToolError> errors = new ArrayList<>();
    private final List<IoLogResult> results = new ArrayList<>();
    /** The credentials of every configured server; here the password of prod/node1. */
    private final SecretScrubber scrubber = new SecretScrubber();

    {
        scrubber.register(TestSecrets.CONFIGURED_PASSWORD);
    }

    @TempDir(factory = GitFreeTempDir.class)
    Path dir;

    private final Logger logger = (Logger) LoggerFactory.getLogger("de.dadecker.inubit");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.TRACE);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
        appender.stop();
    }

    /** Leak check of every call: no credential in an error, a log line or a result. */
    @AfterEach
    void noLeak() {
        List<String> texts = new ArrayList<>();
        errors.forEach(error -> texts.add(error.toString()));
        results.forEach(result -> texts.add(result.toString()));
        appender.list.forEach(event -> texts.add(event.getFormattedMessage()));
        texts.forEach(text -> assertThat(text)
            .doesNotContain(TestSecrets.CONFIGURED_PASSWORD)
            .doesNotContain(TestSecrets.CREDENTIAL_PAYLOAD_VALUE));
    }

    // --- US2-1: the listing ------------------------------------------------------------------

    @Test
    void listsEveryEntryOfTheRunInServerOrderAndNamesTheAnsweringNode() {
        IoLogResult result = listed(request(TEST.value()));

        assertThat(result.node()).isEqualTo(TEST);
        assertThat(result.answeringNode()).isEqualTo("node1");
        assertThat(result.workflow()).isEqualTo(WORKFLOW);
        assertThat(result.processId()).isEqualTo(PROCESS);
        assertThat(result.entries()).containsExactly(CONNECTOR_IN, CONNECTOR_OUT, AUDIT_IN,
            AUDIT_OUT);
        assertThat(result.total()).isEqualTo(4);
        assertThat(result.matching()).isEqualTo(4);
        assertThat(result.truncated()).isFalse();
        assertThat(result.hints()).isEmpty();
        assertThat(result.warnings()).containsExactly(WARNING);
        assertThat(port.calls).containsExactly(new Call(PROCESS, WORKFLOW, V8_1_17,
            START + CLI_TIMEOUT.toNanos()));
    }

    @Test
    void eachEntryCarriesModuleNameIdDirectionIndexAndRef() {
        IoLogEntry first = listed(request(TEST.value())).entries().get(0);

        assertThat(first.moduleName()).isEqualTo("Order Connector");
        assertThat(first.moduleId()).contains("Module-0003");
        assertThat(first.direction()).isEqualTo(IoDirection.INPUT);
        assertThat(first.index()).isEqualTo(1);
        assertThat(first.ref()).isEqualTo(IoMessageRef.of(PROCESS
            + "@@@Order Connector(Module-0003)@@@Input.1"));
    }

    // --- the module filter -------------------------------------------------------------------

    @Test
    void theModuleFilterKeepsTheEntriesWhoseModuleNameEqualsIt() {
        IoLogResult result = listed(request(TEST.value(), Optional.of("Audit Logger")));

        assertThat(result.entries()).containsExactly(AUDIT_IN, AUDIT_OUT);
        assertThat(result.total()).isEqualTo(4);
        assertThat(result.matching()).isEqualTo(2);
        assertThat(result.truncated()).isFalse();
        assertThat(result.hints()).isEmpty();
    }

    @Test
    void theModuleFilterKeepsTheEntriesWhoseModuleIdEqualsIt() {
        IoLogResult result = listed(request(TEST.value(), Optional.of("Module-0003")));

        assertThat(result.entries()).containsExactly(CONNECTOR_IN, CONNECTOR_OUT);
        assertThat(result.total()).isEqualTo(4);
        assertThat(result.matching()).isEqualTo(2);
    }

    @Test
    void aModuleNothingMatchesListsNoEntryWithoutTheEmptyListingHint() {
        IoLogResult result = listed(request(TEST.value(), Optional.of("Audit")));

        assertThat(result.entries()).isEmpty();
        assertThat(result.total()).isEqualTo(4);
        assertThat(result.matching()).isZero();
        assertThat(result.hints()).isEmpty();
    }

    // --- US2-4: a run without logged messages ------------------------------------------------

    @Test
    void anEmptyListingIsAnEmptyResultWithTheHint() {
        port.answer = new IoLogListing("node1", List.of());

        IoLogResult result = listed(request(TEST.value()));

        assertThat(result.entries()).isEmpty();
        assertThat(result.total()).isZero();
        assertThat(result.matching()).isZero();
        assertThat(result.truncated()).isFalse();
        assertThat(result.hints()).containsExactly(EMPTY_HINT);
        assertThat(result.warnings()).containsExactly(WARNING);
    }

    // --- the 500-entry cap -------------------------------------------------------------------

    @Test
    void entriesBeyond500AreOnlyCountedAndTheResultIsTruncated() {
        List<IoLogEntry> entries = IntStream.rangeClosed(1, 501)
            .mapToObj(i -> entry("Order Connector(Module-0003)@@@Input." + i))
            .toList();
        port.answer = new IoLogListing("node1", entries);

        IoLogResult result = listed(request(TEST.value()));

        assertThat(result.entries()).hasSize(500).isEqualTo(entries.subList(0, 500));
        assertThat(result.total()).isEqualTo(501);
        assertThat(result.matching()).isEqualTo(501);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void exactly500EntriesAreNotTruncated() {
        port.answer = new IoLogListing("node1", IntStream.rangeClosed(1, 500)
            .mapToObj(i -> entry("Order Connector(Module-0003)@@@Input." + i))
            .toList());

        IoLogResult result = listed(request(TEST.value()));

        assertThat(result.entries()).hasSize(500);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void theCapCountsTheMatchingEntriesAfterTheFilter() {
        List<IoLogEntry> entries = new ArrayList<>(List.of(AUDIT_IN, AUDIT_OUT));
        IntStream.rangeClosed(1, 501)
            .mapToObj(i -> entry("Order Connector(Module-0003)@@@Output." + i))
            .forEach(entries::add);
        port.answer = new IoLogListing("node1", entries);

        IoLogResult result = listed(request(TEST.value(), Optional.of("Order Connector")));

        assertThat(result.entries()).hasSize(500).doesNotContain(AUDIT_IN, AUDIT_OUT);
        assertThat(result.total()).isEqualTo(503);
        assertThat(result.matching()).isEqualTo(501);
        assertThat(result.truncated()).isTrue();
    }

    // --- entries with an unexpected module part (D-8) ----------------------------------------

    @Test
    void anEntryWithoutAModuleIdIsListedRawWithTheHint() {
        IoLogEntry raw = entry("Legacy Logger@@@Output.2");
        port.answer = new IoLogListing("node1", List.of(CONNECTOR_IN, raw));

        IoLogResult result = listed(request(TEST.value()));

        assertThat(result.entries()).containsExactly(CONNECTOR_IN, raw);
        assertThat(result.entries().get(1).moduleName()).isEqualTo("Legacy Logger");
        assertThat(result.entries().get(1).moduleId()).isEmpty();
        assertThat(result.hints()).containsExactly(RAW_MODULE_HINT);
    }

    @Test
    void anUnexpectedEntryFilteredOutGivesNoHint() {
        port.answer = new IoLogListing("node1", List.of(CONNECTOR_IN,
            entry("Legacy Logger@@@Output.2")));

        IoLogResult result = listed(request(TEST.value(), Optional.of("Module-0003")));

        assertThat(result.entries()).containsExactly(CONNECTOR_IN);
        assertThat(result.hints()).isEmpty();
    }

    // --- step 1: input checks (US2-7; nothing is sent) ---------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"", "Orders@@@Inbound", "Orders###Inbound", "Orders\u0000",
        "Orders\nInbound", "Orders\u001F", "Orders\u007F", "Orders\u0085", "Orders\u009F"})
    void anInvalidWorkflowIsRefusedWithoutEchoAndNothingIsSent(String workflow) {
        ToolError error = errorOf(() -> listed(new IoLogRequest(TEST.value(), workflow, PROCESS,
            Optional.empty(), Optional.empty(), OptionalInt.empty())));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).isEqualTo("Invalid workflow: expected 1–200 characters"
            + " without control characters, '@@@' or '###'");
        assertNothingSent();
        assertThat(infoLines()).containsExactly("read_io_log on -: process 4711, listing -,"
            + " outcome INVALID_INPUT");
    }

    @Test
    void aWorkflowOf201CharactersIsRefusedAnd200AreAccepted() {
        ToolError error = errorOf(() -> listed(new IoLogRequest(TEST.value(), "w".repeat(201),
            PROCESS, Optional.empty(), Optional.empty(), OptionalInt.empty())));
        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertNothingSent();

        listed(new IoLogRequest(TEST.value(), "w".repeat(200), PROCESS, Optional.empty(),
            Optional.empty(), OptionalInt.empty()));
        assertThat(port.calls).hasSize(1);
    }

    @Test
    void theWorkflowLimitCountsCharactersNotUtf16Units() {
        String emoji = "📦";

        listed(new IoLogRequest(TEST.value(), emoji.repeat(200), PROCESS, Optional.empty(),
            Optional.empty(), OptionalInt.empty()));

        assertThat(port.calls).extracting(Call::workflow).containsExactly(emoji.repeat(200));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "47 11", "4711;rm", "4711@@@x", "ä4711"})
    void anInvalidProcessIdIsRefusedWithoutEchoAndNothingIsSent(String processId) {
        ToolError error = errorOf(() -> listed(new IoLogRequest(TEST.value(), WORKFLOW,
            processId, Optional.empty(), Optional.empty(), OptionalInt.empty())));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).isEqualTo("Invalid processId: expected 1–64 characters of"
            + " A-Z, a-z, 0-9, '_' and '-'");
        assertNothingSent();
        assertThat(infoLines()).containsExactly("read_io_log on -: process -, listing -,"
            + " outcome INVALID_INPUT");
    }

    @Test
    void aProcessIdOf65CharactersIsRefused() {
        ToolError error = errorOf(() -> listed(new IoLogRequest(TEST.value(), WORKFLOW,
            "1".repeat(65), Optional.empty(), Optional.empty(), OptionalInt.empty())));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertNothingSent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Order\u0000Connector", "Order\tConnector", "Order\u0090"})
    void anInvalidModuleIsRefusedWithoutEchoAndNothingIsSent(String module) {
        ToolError error = errorOf(() -> listed(request(TEST.value(), Optional.of(module))));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).isEqualTo("Invalid module: expected 1–200 characters without"
            + " control characters");
        assertNothingSent();
    }

    @Test
    void aModuleOf201CharactersIsRefused() {
        ToolError error = errorOf(() -> listed(request(TEST.value(),
            Optional.of("m".repeat(201)))));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertNothingSent();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 100_001})
    void aMaxCharsOutside1To100000IsRefusedAndNothingIsSent(int maxChars) {
        ToolError error = errorOf(() -> listed(new IoLogRequest(TEST.value(), WORKFLOW, PROCESS,
            Optional.empty(), Optional.empty(), OptionalInt.of(maxChars))));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).isEqualTo("Invalid maxChars " + maxChars + " (1–100000)");
        assertThat(error.nextStep()).isEqualTo("Use an integer from 1 to 100000, or leave it out"
            + " for 20000");
        assertNothingSent();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100_000})
    void aMaxCharsAtTheLimitsIsAcceptedForTheListing(int maxChars) {
        IoLogResult result = listed(new IoLogRequest(TEST.value(), WORKFLOW, PROCESS,
            Optional.empty(), Optional.empty(), OptionalInt.of(maxChars)));

        assertThat(result.entries()).hasSize(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Q2Fh7tZk1oVYbP3x9w-aA", "Q2Fh7tZk1oVYbP3x9w-aAAA",
        "Q2Fh7tZk1oVYbP3x9w+aAA", "Q2Fh7tZk1oVYbP3x9w/aAA", "Q2Fh7tZk1oVYbP3x9w=aAA"})
    void anInvalidMessageRefIsRefusedWithoutEchoAndNothingIsSent(String message) {
        ToolError error = errorOf(() -> listed(new IoLogRequest(TEST.value(), WORKFLOW, PROCESS,
            Optional.of(message), Optional.empty(), OptionalInt.empty())));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).isEqualTo("Invalid message: expected the 22-character ref"
            + " of an entry of this run's listing");
        assertNothingSent();
    }

    /** A credential in refused input is never echoed (the leak check runs after the test). */
    @Test
    void aCredentialInRefusedInputIsNotEchoed() {
        attempt(new IoLogRequest(TEST.value(), TestSecrets.CONFIGURED_PASSWORD + "@@@",
            PROCESS, Optional.empty(), Optional.empty(), OptionalInt.empty()));
        attempt(new IoLogRequest(TEST.value(), WORKFLOW, TestSecrets.CONFIGURED_PASSWORD + ";",
            Optional.empty(), Optional.empty(), OptionalInt.empty()));
        attempt(new IoLogRequest(TEST.value(), WORKFLOW, PROCESS, Optional.empty(),
            Optional.of(TestSecrets.CREDENTIAL_PAYLOAD_VALUE + "\n"), OptionalInt.empty()));
        attempt(new IoLogRequest(TEST.value(), WORKFLOW, PROCESS,
            Optional.of(TestSecrets.CREDENTIAL_PAYLOAD_VALUE), Optional.empty(),
            OptionalInt.empty()));

        assertThat(errors).hasSize(4)
            .allSatisfy(error -> assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT));
        assertNothingSent();
    }

    // --- step 2: one server ------------------------------------------------------------------

    @Test
    void aGroupIdIsRefusedWithTheResolversTextAndNothingIsSent() {
        ToolError error = errorOf(() -> listed(request("test")));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).contains("this action needs the id of exactly one");
        assertNothingSent();
    }

    @Test
    void anUnknownServerIsTargetUnknownAndNothingIsSent() {
        ToolError error = errorOf(() -> listed(request("test/node7")));

        assertThat(error.code()).isEqualTo(ErrorCode.TARGET_UNKNOWN);
        assertNothingSent();
    }

    // --- step 3: the opt-in (US2-6) ----------------------------------------------------------

    @Test
    void withoutTheOptInItIsNotConfiguredAndNothingIsSent() {
        ToolError error = errorOf(() -> listed(request(PROD.value())));

        assertThat(error.code()).isEqualTo(ErrorCode.NOT_CONFIGURED);
        assertThat(error.message()).isEqualTo("read_io_log is not enabled for group prod:"
            + " unsupported.ioLogRead is not set");
        assertThat(error.nextStep()).isEqualTo("Set unsupported.ioLogRead: true on group prod and"
            + " restart the MCP client (unsupported INUBIT functionality, see docs/setup.md"
            + " \"Unsupported functions\")");
        assertThat(error.node()).contains(PROD);
        assertNothingSent();
        assertThat(infoLines()).containsExactly("read_io_log on prod/node1: process 4711,"
            + " listing -, outcome NOT_CONFIGURED");
    }

    // --- step 4: the version gate ------------------------------------------------------------

    @Test
    void anUnsupportedVersionIsRefusedByTheGateAndNothingElseIsSent() {
        ToolError error = errorOf(() -> listed(request(NEW.value())));

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.message()).startsWith("read_io_log: ");
        assertThat(detection.requests()).containsExactly(NEW);
        assertThat(gateways.calls).isEmpty();
        assertThat(port.calls).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"UNREACHABLE", "TLS_ERROR", "AUTH_FAILED",
        "TIMEOUT", "MAINTENANCE_MODE", "SECRET_UNRESOLVED"})
    void anUndetectableVersionFailsWithTheDetectionsCodeAndNothingElseIsSent(ErrorCode code) {
        detection.fail(AUTO, code);

        ToolError error = errorOf(() -> listed(request(AUTO.value())));

        assertThat(error.code()).isEqualTo(code);
        assertThat(error.message()).isEqualTo("detection failed");
        assertThat(detection.requests()).containsExactly(AUTO);
        assertThat(gateways.calls).isEmpty();
        assertThat(port.calls).isEmpty();
        assertThat(infoLines()).containsExactly("read_io_log on test/auto: process 4711,"
            + " listing -, outcome " + code);
    }

    // --- step 5: the call deadline and the credentials ---------------------------------------

    @Test
    void credentialsArePreparedWithinTheDeadlineBeforeThePortIsTaken() {
        attempt(request(TEST.value()));

        assertThat(gateways.calls).containsExactly("prepareCredentials " + TEST,
            "ioLog " + TEST);
        assertThat(gateways.bounds).containsExactly(CLI_TIMEOUT);
    }

    @Test
    void theCredentialBoundIsWhatIsLeftOfTheDeadlineAndNeverNegative() {
        tick = CLI_TIMEOUT.toNanos() + 1;

        attempt(request(TEST.value()));

        assertThat(gateways.bounds).containsExactly(Duration.ZERO);
    }

    @Test
    void credentialsNotReadInTimeAreTimeoutNamingCliTimeoutAndThePortIsNotCalled() {
        gateways.state = CredentialState.PENDING;

        ToolError error = errorOf(() -> listed(request(TEST.value())));

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.message()).isEqualTo("read_io_log on test/node1 did not finish within"
            + " cliTimeout (30 s): the login credentials of test/node1 were not read in time");
        assertThat(error.nextStep()).contains("cliTimeout of test/node1");
        assertThat(error.node()).contains(TEST);
        assertThat(gateways.calls).containsExactly("prepareCredentials " + TEST);
        assertThat(port.calls).isEmpty();
    }

    @Test
    void aFailedCredentialFetchPassesThroughAndThePortIsNotCalled() {
        ToolError fetch = ToolError.of(ErrorCode.AUTH_FAILED, "The credentials of test/node1"
            + " could not be read", "cause", "next").withNode(TEST);
        gateways.failure = new ToolErrorException(fetch);

        ToolError error = errorOf(() -> listed(request(TEST.value())));

        assertThat(error).isEqualTo(fetch);
        assertThat(port.calls).isEmpty();
    }

    // --- step 6: the port's failures ---------------------------------------------------------

    @Test
    void aWholeCallFailureOfThePortPassesThrough() {
        ToolError forbidden = ToolError.of(ErrorCode.FORBIDDEN, "test/node1 refused the"
                + " input/output logging for the configured user", "cause",
            "Give the user the right the Workbench's input/output logging view needs")
            .withNode(TEST);
        port.failure = new ToolErrorException(forbidden);

        ToolError error = errorOf(() -> listed(request(TEST.value())));

        assertThat(error).isEqualTo(forbidden);
        assertThat(infoLines()).containsExactly("read_io_log on test/node1: process 4711,"
            + " listing -, outcome FORBIDDEN");
    }

    @Test
    void aPortErrorWithoutANodeNamesTheServer() {
        port.failure = new ToolErrorException(ToolError.of(ErrorCode.CLI_UNAVAILABLE,
            "No INUBIT client", "cause", "next"));

        ToolError error = errorOf(() -> listed(request(TEST.value())));

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).isEqualTo("No INUBIT client");
        assertThat(error.node()).contains(TEST);
    }

    // --- step 7: the INFO line (D-22) --------------------------------------------------------

    @Test
    void aCallLogsOneInfoLineWithoutAnyValue() {
        listed(request(TEST.value(), Optional.of("Audit Logger")));

        assertThat(infoLines()).containsExactly("read_io_log on test/node1: process 4711,"
            + " listing 4, outcome OK");
        assertThat(appender.list).filteredOn(event -> event.getLoggerName()
                .equals(IoLogService.class.getName()))
            .allSatisfy(event -> assertThat(event.getFormattedMessage())
                .doesNotContain(WORKFLOW).doesNotContain("Audit Logger")
                .doesNotContain("Order Connector").doesNotContain("Module-0003"));
    }

    // --- US2-2, FR-021, FR-022: the message read ----------------------------------------------

    @Test
    void aListedRefIsReadMaskedWithTheListingAndTheContractFields() throws IOException {
        String text = "<order><customer>acme</customer><password>"
            + TestSecrets.CREDENTIAL_PAYLOAD_VALUE + "</password><note>"
            + TestSecrets.CONFIGURED_PASSWORD + "</note></order>";
        String masked = "<order><customer>acme</customer><password>${secret:data.password}"
            + "</password><note>***</note></order>";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        port.readAnswer = message(CONNECTOR_IN, bytes);

        IoLogResult result = listed(readRequest(CONNECTOR_IN, OptionalInt.empty()));

        IoLogResult.Message message = result.message().orElseThrow();
        assertThat(message.entry()).isEqualTo(CONNECTOR_IN);
        assertThat(message.kind()).isEqualTo("text");
        assertThat(message.charset()).contains("UTF-8");
        assertThat(message.sizeBytes()).isEqualTo(bytes.length);
        assertThat(message.textChars()).contains(masked.length());
        assertThat(message.text()).contains(masked);
        assertThat(message.cut()).contains(false);
        assertThat(message.detected()).isEmpty();
        // the listing fields of the same call
        assertThat(result.entries()).containsExactly(CONNECTOR_IN, CONNECTOR_OUT, AUDIT_IN,
            AUDIT_OUT);
        assertThat(result.total()).isEqualTo(4);
        assertThat(result.matching()).isEqualTo(4);
        assertThat(result.hints()).isEmpty();
        assertThat(result.warnings()).containsExactly(WARNING);
        // one read within cliExportTimeout, no separate listing; the read is closed
        assertThat(port.reads).containsExactly(new ReadCall(PROCESS, WORKFLOW, CONNECTOR_IN.ref(),
            V8_1_17, START + EXPORT_TIMEOUT.toNanos()));
        assertThat(port.calls).isEmpty();
        assertThat(gateways.bounds).containsExactly(EXPORT_TIMEOUT);
        assertThat(port.closes).isEqualTo(1);
    }

    @Test
    void thePasswordElementIsMaskedBeforeTheCut() throws IOException {
        String text = "<order><password>" + TestSecrets.CREDENTIAL_PAYLOAD_VALUE
            + "</password></order>";
        int maxChars = "<order><password>".length() + 10;
        port.readAnswer = message(CONNECTOR_IN, text.getBytes(StandardCharsets.UTF_8));

        IoLogResult.Message message = listed(readRequest(CONNECTOR_IN, OptionalInt.of(maxChars)))
            .message().orElseThrow();

        assertThat(message.text().orElseThrow()).hasSize(maxChars)
            .startsWith("<order><password>${secret:")
            .doesNotContain(TestSecrets.CREDENTIAL_PAYLOAD_VALUE.substring(0, 4));
        assertThat(message.textChars())
            .contains("<order><password>${secret:data.password}</password></order>".length());
        assertThat(message.cut()).contains(true);
    }

    /** The scrubber holds the password configured for prod/node1; test/node1 is read. */
    @Test
    void aConfiguredPasswordOfAnotherServerIsReplacedBeforeTheCut() throws IOException {
        String text = "x".repeat(10) + TestSecrets.CONFIGURED_PASSWORD + "tail";
        port.readAnswer = message(CONNECTOR_IN, text.getBytes(StandardCharsets.UTF_8));

        IoLogResult.Message message = listed(readRequest(CONNECTOR_IN, OptionalInt.of(15)))
            .message().orElseThrow();

        assertThat(message.text()).contains("x".repeat(10) + "***ta");
        assertThat(message.textChars()).contains(10 + 3 + 4);
        assertThat(message.cut()).contains(true);
    }

    @Test
    void aLatin1MessageIsDecodedAndItsCharsetNamed() throws IOException {
        byte[] latin1 = "Grüße an Order-0001".getBytes(StandardCharsets.ISO_8859_1);
        port.readAnswer = message(AUDIT_OUT, latin1);

        IoLogResult.Message message = listed(readRequest(AUDIT_OUT, OptionalInt.empty()))
            .message().orElseThrow();

        assertThat(message.entry()).isEqualTo(AUDIT_OUT);
        assertThat(message.kind()).isEqualTo("text");
        assertThat(message.charset()).contains("ISO-8859-1");
        assertThat(message.text()).contains("Grüße an Order-0001");
        assertThat(message.sizeBytes()).isEqualTo(latin1.length);
    }

    @Test
    void aBinaryMessageIsOnlySizeAndDetectedTypeWithTheHint() throws IOException {
        byte[] zip = {0x50, 0x4B, 0x03, 0x04, 'a', 'b', 'c', 'd', 'e', 'f'};
        port.readAnswer = message(CONNECTOR_OUT, zip);

        IoLogResult result = listed(readRequest(CONNECTOR_OUT, OptionalInt.empty()));

        IoLogResult.Message message = result.message().orElseThrow();
        assertThat(message.kind()).isEqualTo("binary");
        assertThat(message.sizeBytes()).isEqualTo(10);
        assertThat(message.detected()).contains(BinarySignature.ZIP);
        assertThat(message.text()).isEmpty();
        assertThat(message.textChars()).isEmpty();
        assertThat(message.charset()).isEmpty();
        assertThat(result.hints()).containsExactly(BINARY_HINT);
        assertThat(port.closes).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100_000})
    void theMaskedTextIsCutAtMaxCharsWithItsFullLength(int maxChars) throws IOException {
        port.readAnswer = message(CONNECTOR_IN,
            "a".repeat(100_001).getBytes(StandardCharsets.UTF_8));

        IoLogResult.Message message = listed(readRequest(CONNECTOR_IN,
            OptionalInt.of(maxChars))).message().orElseThrow();

        assertThat(message.text()).contains("a".repeat(maxChars));
        assertThat(message.textChars()).contains(100_001);
        assertThat(message.sizeBytes()).isEqualTo(100_001);
        assertThat(message.cut()).contains(true);
    }

    @Test
    void theTextIsCutAt20000WithoutMaxChars() throws IOException {
        port.readAnswer = message(CONNECTOR_IN,
            "a".repeat(20_001).getBytes(StandardCharsets.UTF_8));

        IoLogResult.Message message = listed(readRequest(CONNECTOR_IN, OptionalInt.empty()))
            .message().orElseThrow();

        assertThat(message.text().orElseThrow()).hasSize(20_000);
        assertThat(message.cut()).contains(true);
    }

    /** D-9: over 10 MiB nothing of the file is shown; the read is closed (the file deleted). */
    @Test
    void aMessageOver10MiBIsTooLargeWithItsSizeAndTheHint() throws IOException {
        long size = QueueEntryBounds.MAX_DATA_BYTES + 1;
        byte[] big = "a".repeat((int) size).getBytes(StandardCharsets.UTF_8);
        port.readAnswer = message(CONNECTOR_IN, big);

        IoLogResult result = listed(readRequest(CONNECTOR_IN, OptionalInt.empty()));

        IoLogResult.Message message = result.message().orElseThrow();
        assertThat(message.kind()).isEqualTo("tooLarge");
        assertThat(message.sizeBytes()).isEqualTo(size);
        assertThat(message.text()).isEmpty();
        assertThat(message.textChars()).isEmpty();
        assertThat(message.charset()).isEmpty();
        assertThat(message.cut()).isEmpty();
        assertThat(result.hints()).containsExactly("The message has 10485761 bytes and is too"
            + " large to show (limit 10 MiB)");
        assertThat(port.closes).isEqualTo(1);
    }

    @Test
    void aMessageOfExactly10MiBIsShown() throws IOException {
        int size = (int) QueueEntryBounds.MAX_DATA_BYTES;
        port.readAnswer = message(CONNECTOR_IN,
            "a".repeat(size).getBytes(StandardCharsets.UTF_8));

        IoLogResult.Message message = listed(readRequest(CONNECTOR_IN, OptionalInt.of(5)))
            .message().orElseThrow();

        assertThat(message.kind()).isEqualTo("text");
        assertThat(message.sizeBytes()).isEqualTo(size);
        assertThat(message.text()).contains("aaaaa");
        assertThat(message.textChars()).contains(size);
    }

    // --- US2-5, FR-023: an empty answer --------------------------------------------------------

    @Test
    void anEmptyMessageIsKindEmptyWithTheHintAndNotAnError() throws IOException {
        port.readAnswer = message(CONNECTOR_IN, new byte[0]);

        IoLogResult result = listed(readRequest(CONNECTOR_IN, OptionalInt.empty()));

        IoLogResult.Message message = result.message().orElseThrow();
        assertThat(message.kind()).isEqualTo("empty");
        assertThat(message.sizeBytes()).isZero();
        assertThat(message.text()).isEmpty();
        assertThat(result.hints()).containsExactly(EMPTY_MESSAGE_HINT);
        assertThat(port.closes).isEqualTo(1);
        assertThat(infoLines()).containsExactly("read_io_log on test/node1: process 4711,"
            + " listing 4, message " + CONNECTOR_IN.ref().value() + ", size 0, outcome OK");
    }

    // --- US2-3, FR-021: a ref the listing does not have ----------------------------------------

    @Test
    void aRefNotInTheListingIsInvalidInputWithTheContractText() {
        port.readAnswer = new IoLogPort.IoLogRead.NoMatch(LISTING);
        String ref = "Q2Fh7tZk1oVYbP3x9w-aAA";

        ToolError error = errorOf(() -> listed(new IoLogRequest(TEST.value(), WORKFLOW, PROCESS,
            Optional.of(ref), Optional.empty(), OptionalInt.empty())));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).isEqualTo("Message Q2Fh7tZk1oVYbP3x9w-aAA is not in the"
            + " input/output logging of process 4711 of Orders-Inbound on test/node1");
        assertThat(error.nextStep()).isEqualTo("Call read_io_log without message and use a"
            + " listed ref");
        assertThat(error.node()).contains(TEST);
        assertThat(port.reads).extracting(ReadCall::ref).containsExactly(new IoMessageRef(ref));
        // no message: no result, and no private copy whose close could be counted
        assertThat(results).isEmpty();
        assertThat(port.closes).isZero();
        assertThat(infoLines()).containsExactly("read_io_log on test/node1: process 4711,"
            + " listing 4, message Q2Fh7tZk1oVYbP3x9w-aAA, size -, outcome INVALID_INPUT");
    }

    // --- the read's failures -------------------------------------------------------------------

    @Test
    void credentialsNotReadInTimeForAReadAreTimeoutNamingCliExportTimeout() {
        gateways.state = CredentialState.PENDING;

        ToolError error = errorOf(() -> listed(readRequest(CONNECTOR_IN, OptionalInt.empty())));

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.message()).isEqualTo("read_io_log on test/node1 did not finish within"
            + " cliExportTimeout (120 s): the login credentials of test/node1 were not read in"
            + " time");
        assertThat(error.nextStep()).contains("cliExportTimeout of test/node1");
        assertThat(port.reads).isEmpty();
    }

    @Test
    void aReadFailureOfThePortPassesThroughNamingTheServer() {
        port.failure = new ToolErrorException(ToolError.of(ErrorCode.CLI_UNAVAILABLE,
            "No INUBIT client", "cause", "next"));

        ToolError error = errorOf(() -> listed(readRequest(CONNECTOR_IN, OptionalInt.empty())));

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.node()).contains(TEST);
        assertThat(infoLines()).containsExactly("read_io_log on test/node1: process 4711,"
            + " listing -, message " + CONNECTOR_IN.ref().value() + ", size -, outcome"
            + " CLI_UNAVAILABLE");
    }

    @Test
    void anUnreadablePrivateCopyIsInternalAndTheReadIsClosed() {
        port.readAnswer = new IoLogPort.IoLogRead.Message(LISTING, CONNECTOR_IN,
            dir.resolve("missing.bin"), 12, () -> port.closes++);

        ToolError error = errorOf(() -> listed(readRequest(CONNECTOR_IN, OptionalInt.empty())));

        assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(error.node()).contains(TEST);
        assertThat(port.closes).isEqualTo(1);
    }

    /** D-22: the ref and the size only; never the text, a name or the workflow. */
    @Test
    void aReadLogsOneInfoLineWithTheRefAndTheSizeOnly() throws IOException {
        byte[] bytes = ("<order><password>" + TestSecrets.CREDENTIAL_PAYLOAD_VALUE
            + "</password>" + TestSecrets.CONFIGURED_PASSWORD + "</order>")
            .getBytes(StandardCharsets.UTF_8);
        port.readAnswer = message(CONNECTOR_IN, bytes);

        listed(readRequest(CONNECTOR_IN, OptionalInt.empty()));

        assertThat(infoLines()).containsExactly("read_io_log on test/node1: process 4711,"
            + " listing 4, message " + CONNECTOR_IN.ref().value() + ", size " + bytes.length
            + ", outcome OK");
        assertThat(appender.list).filteredOn(event -> event.getLoggerName()
                .equals(IoLogService.class.getName()))
            .allSatisfy(event -> assertThat(event.getFormattedMessage())
                .doesNotContain(WORKFLOW).doesNotContain("Order Connector")
                .doesNotContain("Module-0003").doesNotContain("<order>"));
    }

    // --- helpers -----------------------------------------------------------------------------

    private IoLogService service() {
        return new IoLogService(new TargetResolver(List.of(TEST, PROD, NEW, AUTO)),
            node -> !node.group().value().equals("prod"),
            FakeVersionDetection.gateWithoutClients(versions, detection, MATRIX), gateways,
            node -> CLI_TIMEOUT, node -> EXPORT_TIMEOUT, scrubber::scrub,
            () -> nanoTime.getAndAdd(tick));
    }

    private static IoLogEntry entry(String afterProcess) {
        return IoLogEntry.parse(PROCESS, PROCESS + "@@@" + afterProcess);
    }

    private static IoLogRequest request(String target) {
        return request(target, Optional.empty());
    }

    private static IoLogRequest request(String target, Optional<String> module) {
        return new IoLogRequest(target, WORKFLOW, PROCESS, Optional.empty(), module,
            OptionalInt.empty());
    }

    private static IoLogRequest readRequest(IoLogEntry entry, OptionalInt maxChars) {
        return new IoLogRequest(TEST.value(), WORKFLOW, PROCESS, Optional.of(entry.ref().value()),
            Optional.empty(), maxChars);
    }

    /** A read of {@code entry} whose private copy holds {@code bytes}; its close is counted. */
    private IoLogPort.IoLogRead message(IoLogEntry entry, byte[] bytes) throws IOException {
        Path file = Files.write(dir.resolve("io-message.bin"), bytes);
        return new IoLogPort.IoLogRead.Message(LISTING, entry, file, bytes.length,
            () -> port.closes++);
    }

    /** Calls the service and keeps the result for the leak check. */
    private IoLogResult listed(IoLogRequest request) {
        IoLogResult result = service().read(request);
        results.add(result);
        return result;
    }

    /** Runs a call whatever its outcome; a tool error is kept for the leak check. */
    private void attempt(IoLogRequest request) {
        Throwable thrown = catchThrowable(() -> listed(request));
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

    private List<String> infoLines() {
        return appender.list.stream()
            .filter(event -> event.getLoggerName().equals(IoLogService.class.getName()))
            .filter(event -> event.getLevel() == Level.INFO)
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    }

    private void assertNothingSent() {
        assertThat(detection.requests()).isEmpty();
        assertThat(gateways.calls).isEmpty();
        assertThat(port.calls).isEmpty();
        assertThat(port.reads).isEmpty();
    }

    private record ReadCall(String processId, String workflow, IoMessageRef ref,
        InubitVersion version, long deadlineNanos) {
    }

    private record Call(String processId, String workflow, InubitVersion version,
        long deadlineNanos) {
    }

    /**
     * Records each listing and read; answers {@code answer} or {@code readAnswer}, or throws
     * {@code failure}; counts the closes of the reads it answered.
     */
    private static final class FakePort implements IoLogPort {

        final List<Call> calls = new ArrayList<>();
        final List<ReadCall> reads = new ArrayList<>();
        IoLogListing answer = LISTING;
        IoLogRead readAnswer;
        ToolErrorException failure;
        int closes;

        @Override
        public IoLogListing list(String processId, String workflow, InubitVersion version,
            long deadlineNanos) {
            calls.add(new Call(processId, workflow, version, deadlineNanos));
            if (failure != null) {
                throw failure;
            }
            return answer;
        }

        @Override
        public IoLogRead read(String processId, String workflow, IoMessageRef ref,
            InubitVersion version, long deadlineNanos) {
            reads.add(new ReadCall(processId, workflow, ref, version, deadlineNanos));
            if (failure != null) {
                throw failure;
            }
            return readAnswer;
        }
    }

    /**
     * Only {@code prepareCredentials} and {@code ioLog}, recorded in call order; every other port
     * is an assertion error.
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
        public IoLogPort ioLog(NodeId server) {
            calls.add("ioLog " + server);
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
}
