package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.application.QueueEntryService.RawEntryRead;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.EntryPart;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.domain.model.ProcessRows;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.domain.model.QueueEntries;
import de.dadecker.inubit.mcp.domain.model.QueueEntryBounds;
import de.dadecker.inubit.mcp.domain.model.QueueEntryRef;
import de.dadecker.inubit.mcp.domain.model.QueueEntryResult;
import de.dadecker.inubit.mcp.domain.model.QueueEntryRow;
import de.dadecker.inubit.mcp.domain.model.RawEntryContent;
import de.dadecker.inubit.mcp.domain.model.RawEntryContent.PartResult;
import de.dadecker.inubit.mcp.domain.model.RawVariable;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.domain.port.QueueEntryContentPort;
import de.dadecker.inubit.mcp.infra.ResultJson;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Feature 020 (T023): {@code read_queue_entry} up to the content read — input checks, target,
 * opt-in before the gate and any request, the gate with detection, the candidate rules of
 * research D-9, the 10 MiB pre-check, the port call with the admitted version and the re-read
 * after "not found" (contracts/mcp-tools-delta.md "Errors"). T024: the result built from it —
 * classification, scrubbing of known credentials, masking, bounds (FR-008…FR-011, D-10…D-12;
 * contracts/mcp-tools-delta.md "Output"). Fakes of {@link ProcessQueryPort#queueEntries} and
 * {@link QueueEntryContentPort}; no WireMock, no process.
 */
class QueueEntryServiceTest {

    private static final NodeId TEST = NodeId.parse("test/node1");
    private static final NodeId TEST2 = NodeId.parse("test/node2");
    private static final NodeId PROD = NodeId.parse("prod/node1");
    private static final NodeId LEGACY = NodeId.parse("legacy/node1");
    private static final String PID = "1234";
    private static final Instant NOW = Instant.parse("2026-11-10T09:00:00Z");
    private static final Instant SINCE = Instant.parse("2026-11-10T08:15:30Z");
    private static final String FILE = "Module-0001(4711)@1234@0a1b2c3d";
    private static final String ORDER = "<Order><Id>Order-0001</Id></Order>";
    private static final InubitVersion V8_1_17 = InubitVersion.parse("8.1.17").orElseThrow();

    private final MutableClock clock = new MutableClock(NOW);
    private final Map<NodeId, VersionSetting> settings = Map.of(TEST, VersionSetting.AUTO,
        TEST2, VersionSetting.AUTO, PROD, VersionSetting.AUTO, LEGACY, VersionSetting.V6_1);
    private final NodeVersions versions = new NodeVersions(clock, settings::get,
        node -> Duration.ofMinutes(10));
    private final FakeVersionDetection detection = new FakeVersionDetection(versions)
        .answer(TEST, "8.1.17").answer(TEST2, "8.1.17").answer(PROD, "8.1.17");
    private final FakeRows rows = new FakeRows();
    private final FakeContent content = new FakeContent();
    private final SecretScrubber scrubber = new SecretScrubber();

    {
        scrubber.register(TestSecrets.CONFIGURED_PASSWORD);
    }

    private final QueueEntryService service = new QueueEntryService(
        new TargetResolver(List.of(TEST, TEST2, PROD, LEGACY)),
        node -> !node.group().value().equals("prod"),
        FakeVersionDetection.gateWithoutClients(versions, detection,
            CapabilityMatrix.production()),
        node -> rows.port(node), node -> content.port(node), scrubber::scrub);

    private final List<ToolError> errors = new ArrayList<>();

    // --- input checks -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "0", "01234", "-1", "12345678901234567890",
        "0f0e0d0c-0000-4000-8000-000000000001", "12 34"})
    void invalidProcessIdIsRefusedBeforeAnyRequest(String processId) {
        ToolError error = errorOf(() -> read(TEST.value(), processId, Optional.empty(),
            OptionalInt.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).startsWith("Invalid processId '");
        assertNothingSent();
    }

    @Test
    void longestProcessIdIsAccepted() {
        rows.answer(entries(row("1234567890123456789", FILE, ProcessState.ERROR, "Error")));
        content.answer(read());

        RawEntryRead read = read(TEST.value(), "1234567890123456789", Optional.empty(),
            OptionalInt.empty());

        assertThat(read.row().processId()).isEqualTo("1234567890123456789");
        assertThat(rows.requests).containsExactly("1234567890123456789");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a/b", "a\\b", "a\u0000b", "a\tb", "a\nb", "a\u007Fb",
        "a\u0085b", "a\u009Fb"})
    void invalidFileNameIsRefusedWithoutEchoingIt(String fileName) {
        ToolError error = errorOf(() -> read(TEST.value(), PID, Optional.of(fileName),
            OptionalInt.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).isEqualTo("Invalid fileName: expected 1–255 characters"
            + " without '/', '\\', NUL or other control characters");
        assertNothingSent();
    }

    @Test
    void fileNameOf256CharactersIsRefusedAnd255AreAccepted() {
        ToolError error = errorOf(() -> read(TEST.value(), PID, Optional.of("x".repeat(256)),
            OptionalInt.empty()));
        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).startsWith("Invalid fileName: ");
        assertNothingSent();

        String longest = "x".repeat(255);
        rows.answer(entries(row(PID, longest, ProcessState.ERROR, "Error")));
        content.answer(read());
        assertThat(read(TEST.value(), PID, Optional.of(longest), OptionalInt.empty()).row()
            .fileName()).isEqualTo(longest);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 100_001, Integer.MAX_VALUE, Integer.MIN_VALUE})
    void maxCharsOutsideTheRangeIsRefused(int maxChars) {
        ToolError error = errorOf(() -> read(TEST.value(), PID, Optional.empty(),
            OptionalInt.of(maxChars)));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).isEqualTo("Invalid maxChars " + maxChars + " (1–100000)");
        assertNothingSent();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100_000})
    void maxCharsAtTheBoundsIsAccepted(int maxChars) {
        rows.answer(entries(errorRow(FILE)));
        content.answer(read());

        assertThat(read(TEST.value(), PID, Optional.empty(), OptionalInt.of(maxChars))
            .maxChars()).isEqualTo(maxChars);
    }

    @Test
    void maxCharsDefaultsTo20000() {
        rows.answer(entries(errorRow(FILE)));
        content.answer(read());

        assertThat(read(TEST.value(), PID, Optional.empty(), OptionalInt.empty()).maxChars())
            .isEqualTo(QueueEntryBounds.DEFAULT_MAX_CHARS).isEqualTo(20_000);
    }

    // --- target, opt-in, gate -----------------------------------------------------------------

    @Test
    void groupIdIsRefusedWithTheResolverText() {
        ToolError error = errorOf(() -> read("test", PID, Optional.empty(),
            OptionalInt.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).isEqualTo("'test' is the id of one group; this action needs"
            + " the id of exactly one node (<group>/<node>)");
        assertNothingSent();
    }

    @Test
    void unknownNodeIsTargetUnknown() {
        ToolError error = errorOf(() -> read("test/node9", PID, Optional.empty(),
            OptionalInt.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.TARGET_UNKNOWN);
        assertNothingSent();
    }

    /** US2-2: refused before the version detection, the queueLog lookup and the helper. */
    @Test
    void groupWithoutOptInIsNotConfiguredAndNothingIsSent() {
        ToolError error = errorOf(() -> read(PROD.value(), PID, Optional.empty(),
            OptionalInt.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.NOT_CONFIGURED);
        assertThat(error.message()).isEqualTo("read_queue_entry is not enabled for group prod:"
            + " unsupported.queueEntryRead is not set");
        assertThat(error.nextStep()).isEqualTo("Set unsupported.queueEntryRead: true on group"
            + " prod and restart the MCP client (unsupported INUBIT functionality, see"
            + " docs/setup.md \"Unsupported functions\")");
        assertThat(error.node()).contains(PROD);
        assertNothingSent();
    }

    @Test
    void optInIsCheckedBeforeTheGate() {
        QueueEntryService withoutOptIn = new QueueEntryService(
            new TargetResolver(List.of(LEGACY)), node -> false,
            FakeVersionDetection.gateWithoutClients(versions, detection,
                CapabilityMatrix.production()),
            node -> rows.port(node), node -> content.port(node), scrubber::scrub);

        ToolError error = errorOf(() -> withoutOptIn.readRaw(LEGACY.value(), PID,
            Optional.empty(), OptionalInt.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.NOT_CONFIGURED);
        assertNothingSent();
    }

    @Test
    void unsupportedVersionIsRefusedByTheGateBeforeTheLookup() {
        ToolError error = errorOf(() -> read(LEGACY.value(), PID, Optional.empty(),
            OptionalInt.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.message()).startsWith("read_queue_entry: ").contains("6.1")
            .contains("supported: 8.1, 9.0");
        assertThat(rows.requests).isEmpty();
        assertThat(content.calls).isEmpty();
    }

    @Test
    void nineZeroIsAdmittedSinceItsRecording() {
        FakeVersionDetection nineZero = new FakeVersionDetection(versions)
            .answer(TEST, "9.0.7");
        QueueEntryService service90 = new QueueEntryService(new TargetResolver(List.of(TEST)),
            node -> true, FakeVersionDetection.gateWithoutClients(versions, nineZero,
                CapabilityMatrix.production()),
            node -> rows.port(node), node -> content.port(node), scrubber::scrub);

        rows.answer(entries(errorRow(FILE)));
        content.answer(read());

        service90.readRaw(TEST.value(), PID, Optional.empty(), OptionalInt.empty());

        assertThat(rows.requests).as("T038: 9.0 is supported").isNotEmpty();
        assertThat(content.calls).isNotEmpty();
    }

    /** US3-4: the detection's own error, no lookup and no content request. */
    @Test
    void undetectableVersionFailsWithTheDetectionError() {
        detection.fail(TEST, ErrorCode.UNREACHABLE);

        ToolError error = errorOf(() -> read(TEST.value(), PID, Optional.empty(),
            OptionalInt.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.UNREACHABLE);
        assertThat(detection.requests()).containsExactly(TEST);
        assertThat(rows.requests).isEmpty();
        assertThat(content.calls).isEmpty();
    }

    // --- candidates ---------------------------------------------------------------------------

    @Test
    void oneErrorRowAmongOthersIsReadWithTheAdmittedVersion() {
        QueueEntryRow error = errorRow(FILE);
        rows.answer(entries(
            row(PID, "Module-0002(4712)@1234@0a1b2c3e", ProcessState.WAITING, "Waiting"),
            error,
            row(PID, "Module-0003(4713)@1234@0a1b2c3f", ProcessState.OTHER, "Retry")));
        RawEntryContent raw = read();
        content.answer(raw);

        RawEntryRead read = read(TEST.value(), PID, Optional.empty(), OptionalInt.empty());

        assertThat(read.node()).isEqualTo(TEST);
        assertThat(read.row()).isEqualTo(error);
        assertThat(read.content()).isSameAs(raw);
        assertThat(read.warnings()).isEmpty();
        assertThat(rows.requests).containsExactly(PID);
        assertThat(content.calls).singleElement().satisfies(call -> {
            assertThat(call.node()).isEqualTo(TEST);
            assertThat(call.ref()).isEqualTo(new QueueEntryRef(TEST, "Workflow-0001", FILE, 772));
            assertThat(call.version()).isEqualTo(V8_1_17);
            assertThat(call.parts()).containsExactlyInAnyOrder(EntryPart.DATA,
                EntryPart.VARIABLES);
        });
    }

    @Test
    void noRowIsNotFound() {
        rows.answer(new QueueEntries(List.of(), 0));

        ToolError error = errorOf(() -> read(TEST.value(), PID, Optional.empty(),
            OptionalInt.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(error.message()).isEqualTo("Process 1234 is not (or no longer) in the Queue"
            + " Manager of test/node1; nothing was read");
        assertThat(error.nextStep()).isEqualTo("Look the instance up again with find_processes");
        assertThat(error.node()).contains(TEST);
        assertThat(content.calls).isEmpty();
    }

    @Test
    void rowsWithoutErrorAreInvalidInputListingTheirStates() {
        rows.answer(entries(
            row(PID, "Module-0002(4712)@1234@0a1b2c3e", ProcessState.WAITING, "Waiting"),
            row(PID, "Module-0003(4713)@1234@0a1b2c3f", ProcessState.OTHER, "Retry")));

        ToolError error = errorOf(() -> read(TEST.value(), PID, Optional.empty(),
            OptionalInt.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).isEqualTo("read_queue_entry reads only ERROR entries;"
            + " process 1234 on test/node1 has: Waiting (Module-0002(4712)@1234@0a1b2c3e),"
            + " Retry (Module-0003(4713)@1234@0a1b2c3f)");
        assertThat(error.nextStep()).isEqualTo("Wait until the entry is in ERROR, or use"
            + " find_processes");
        assertThat(error.node()).contains(TEST);
        assertThat(content.calls).isEmpty();
    }

    /** US1-7: several ERROR entries are listed; with one of the file names that entry is read. */
    @Test
    void severalErrorRowsAreListedAndFileNameSelectsOne() {
        QueueEntryRow caller = new QueueEntryRow(PID, Optional.empty(), ProcessState.ERROR,
            "Error", SINCE, "Workflow-0001", Optional.of("Module-0001"), Optional.of("Throw"),
            FILE, 772, new TreeMap<>());
        QueueEntryRow sub = new QueueEntryRow(PID, Optional.empty(), ProcessState.ERROR,
            "Error", SINCE.minusSeconds(1), "Workflow-0002", Optional.of("Module-0003"),
            Optional.empty(), "Module-0003(4713)@1234@0a1b2c3f", 10, new TreeMap<>());
        rows.answer(entries(caller, sub));

        ToolError error = errorOf(() -> read(TEST.value(), PID, Optional.empty(),
            OptionalInt.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).isEqualTo("Process 1234 on test/node1 has 2 ERROR entries:"
            + " Module-0001(4711)@1234@0a1b2c3d (Workflow-0001, Module-0001,"
            + " 2026-11-10T08:15:30Z); Module-0003(4713)@1234@0a1b2c3f (Workflow-0002,"
            + " Module-0003, 2026-11-10T08:15:29Z)");
        assertThat(error.nextStep()).isEqualTo("Repeat the call with fileName set to one of"
            + " them");
        assertThat(content.calls).isEmpty();

        rows.answer(entries(caller, sub));
        content.answer(read());
        RawEntryRead read = read(TEST.value(), PID, Optional.of(sub.fileName()),
            OptionalInt.empty());

        assertThat(read.row()).isEqualTo(sub);
        assertThat(content.calls).singleElement().satisfies(call -> assertThat(call.ref())
            .isEqualTo(new QueueEntryRef(TEST, "Workflow-0002", sub.fileName(), 10)));
    }

    @Test
    void candidateWithoutModuleLeavesItOut() {
        QueueEntryRow withoutModule = new QueueEntryRow(PID, Optional.empty(),
            ProcessState.ERROR, "Error", SINCE.minusSeconds(1), "Workflow-0002",
            Optional.empty(), Optional.empty(), "Module-0003(4713)@1234@0a1b2c3f", 10,
            new TreeMap<>());
        rows.answer(entries(errorRow(FILE), withoutModule));

        ToolError error = errorOf(() -> read(TEST.value(), PID, Optional.empty(),
            OptionalInt.empty()));

        assertThat(error.message()).endsWith("; Module-0003(4713)@1234@0a1b2c3f (Workflow-0002,"
            + " 2026-11-10T08:15:29Z)");
    }

    @Test
    void candidateListIsBoundedTo20() {
        List<QueueEntryRow> many = IntStream.range(0, 25)
            .mapToObj(i -> new QueueEntryRow(PID, Optional.empty(), ProcessState.ERROR,
                "Error", SINCE.minusSeconds(i), "Workflow-0001", Optional.of("Module-0001"),
                Optional.empty(), "Module-0001(" + i + ")@1234@0a1b2c3d", 1, new TreeMap<>()))
            .toList();
        rows.answer(new QueueEntries(many, 25));

        ToolError error = errorOf(() -> read(TEST.value(), PID, Optional.empty(),
            OptionalInt.empty()));

        assertThat(error.message()).startsWith("Process 1234 on test/node1 has 25 ERROR"
            + " entries: ").contains("Module-0001(19)@1234@0a1b2c3d")
            .doesNotContain("Module-0001(20)@").endsWith("; and 5 more");
    }

    @Test
    void stateListIsBoundedTo20() {
        List<QueueEntryRow> waiting = IntStream.range(0, 25)
            .mapToObj(i -> new QueueEntryRow(PID, Optional.empty(), ProcessState.WAITING,
                "Waiting", SINCE.minusSeconds(i), "Workflow-0001", Optional.empty(),
                Optional.empty(), "Module-0001(" + i + ")@1234@0a1b2c3d", 1, new TreeMap<>()))
            .toList();
        rows.answer(new QueueEntries(waiting, 25));

        ToolError error = errorOf(() -> read(TEST.value(), PID, Optional.empty(),
            OptionalInt.empty()));

        assertThat(error.message()).contains("Waiting (Module-0001(19)@1234@0a1b2c3d)")
            .doesNotContain("Module-0001(20)@").endsWith(", and 5 more");
    }

    @Test
    void truncatedRowsAreNamedInEveryCandidateMessage() {
        List<QueueEntryRow> hundred = IntStream.range(0, 100)
            .mapToObj(i -> new QueueEntryRow(PID, Optional.empty(),
                i < 2 ? ProcessState.ERROR : ProcessState.WAITING, i < 2 ? "Error" : "Waiting",
                SINCE.minusSeconds(i), "Workflow-0001", Optional.empty(), Optional.empty(),
                "Module-0001(" + i + ")@1234@0a1b2c3d", 1, new TreeMap<>()))
            .toList();
        List<QueueEntryRow> noError = hundred.stream()
            .filter(r -> r.state() != ProcessState.ERROR).toList();

        rows.answer(new QueueEntries(hundred, 250));
        assertThat(errorOf(() -> read(TEST.value(), PID, Optional.empty(), OptionalInt.empty()))
            .message()).endsWith("; only 100 of 250 rows were read");

        rows.answer(new QueueEntries(noError, 250));
        assertThat(errorOf(() -> read(TEST.value(), PID, Optional.empty(), OptionalInt.empty()))
            .message()).endsWith("; only 98 of 250 rows were read and none is in ERROR");

        rows.answer(new QueueEntries(hundred, 250));
        ToolError unknown = errorOf(() -> read(TEST.value(), PID, Optional.of("unknown-file"),
            OptionalInt.empty()));
        assertThat(unknown.code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(unknown.message()).endsWith("; only 100 of 250 rows were read");

        assertThat(content.calls).isEmpty();
    }

    @Test
    void oneErrorRowAmongTruncatedRowsIsRead() {
        List<QueueEntryRow> hundred = IntStream.range(0, 100)
            .mapToObj(i -> new QueueEntryRow(PID, Optional.empty(),
                i == 0 ? ProcessState.ERROR : ProcessState.WAITING,
                i == 0 ? "Error" : "Waiting", SINCE.minusSeconds(i), "Workflow-0001",
                Optional.empty(), Optional.empty(), "Module-0001(" + i + ")@1234@0a1b2c3d", 1,
                new TreeMap<>()))
            .toList();
        rows.answer(new QueueEntries(hundred, 250));
        content.answer(read());

        assertThat(read(TEST.value(), PID, Optional.empty(), OptionalInt.empty()).row()
            .fileName()).isEqualTo("Module-0001(0)@1234@0a1b2c3d");
    }

    @Test
    void fileNameOfANonErrorRowIsInvalidInputWithItsState() {
        String waiting = "Module-0002(4712)@1234@0a1b2c3e";
        rows.answer(entries(errorRow(FILE),
            row(PID, waiting, ProcessState.WAITING, "Waiting")));

        ToolError error = errorOf(() -> read(TEST.value(), PID, Optional.of(waiting),
            OptionalInt.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).isEqualTo("read_queue_entry reads only ERROR entries;"
            + " process 1234 on test/node1 has: Waiting (Module-0002(4712)@1234@0a1b2c3e)");
        assertThat(content.calls).isEmpty();
    }

    @Test
    void unknownFileNameIsNotFound() {
        rows.answer(entries(errorRow(FILE)));

        ToolError error = errorOf(() -> read(TEST.value(), PID,
            Optional.of("Module-0009(1)@1234@0a1b2c3d"), OptionalInt.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(error.message()).isEqualTo("Queue entry Module-0009(1)@1234@0a1b2c3d of"
            + " process 1234 is not in the Queue Manager of test/node1; nothing was read");
        assertThat(error.node()).contains(TEST);
        assertThat(content.calls).isEmpty();
    }

    // --- rows without queue file (T034-F1) ----------------------------------------------------

    @Test
    void onlyErrorRowsWithoutQueueFileAreInvalidInputAndNothingIsLaunched() {
        rows.answer(new QueueEntries(List.of(), List.of(ProcessState.ERROR, ProcessState.ERROR),
            2));

        ToolError error = errorOf(() -> read(TEST.value(), PID, Optional.empty(),
            OptionalInt.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).isEqualTo("Process 1234 on test/node1 has 2 ERROR entries"
            + " in the Queue Manager without a queue file (fileSize 0); there is no content to"
            + " read");
        assertThat(error.nextStep()).isEqualTo("Read their error texts with query_logs"
            + " (logType queueLog) for process 1234");
        assertThat(error.node()).contains(TEST);
        assertThat(content.calls).isEmpty();
        TestSecrets.assertNoLeak(error.message() + " " + error.likelyCause() + " "
            + error.nextStep());
    }

    @Test
    void anErrorRowWithoutQueueFileBesideRowsWithFileNotInErrorIsInvalidInput() {
        rows.answer(new QueueEntries(
            List.of(row(PID, "Module-0002(4712)@1234@0a1b2c3e", ProcessState.WAITING,
                "Waiting")),
            List.of(ProcessState.ERROR, ProcessState.WAITING), 3));

        ToolError error = errorOf(() -> read(TEST.value(), PID, Optional.empty(),
            OptionalInt.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).isEqualTo("Process 1234 on test/node1 has 1 ERROR entry"
            + " in the Queue Manager without a queue file (fileSize 0); there is no content to"
            + " read");
        assertThat(content.calls).isEmpty();
    }

    @Test
    void rowsWithoutQueueFileAreIgnoredBesideOneErrorRowWithFile() {
        QueueEntryRow error = errorRow(FILE);
        rows.answer(new QueueEntries(List.of(error), List.of(ProcessState.ERROR), 2));
        content.answer(read());

        RawEntryRead read = read(TEST.value(), PID, Optional.empty(), OptionalInt.empty());

        assertThat(read.row()).isEqualTo(error);
        assertThat(content.calls).singleElement().satisfies(call -> assertThat(call.ref())
            .isEqualTo(new QueueEntryRef(TEST, "Workflow-0001", FILE, 772)));
    }

    @Test
    void rowsWithoutQueueFileAreNotCandidates() {
        rows.answer(new QueueEntries(List.of(errorRow(FILE), row(PID,
            "Module-0003(4713)@1234@0a1b2c3f", ProcessState.ERROR, "Error")),
            List.of(ProcessState.ERROR), 3));

        ToolError error = errorOf(() -> read(TEST.value(), PID, Optional.empty(),
            OptionalInt.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).startsWith("Process 1234 on test/node1 has 2 ERROR entries:"
            + " Module-0001(4711)@1234@0a1b2c3d").doesNotContain("without a queue file");
        assertThat(content.calls).isEmpty();
    }

    @Test
    void aFileNameIsNotFoundAmongRowsWithoutQueueFile() {
        rows.answer(new QueueEntries(List.of(), List.of(ProcessState.ERROR), 1));

        ToolError error = errorOf(() -> read(TEST.value(), PID, Optional.of(FILE),
            OptionalInt.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(error.message()).isEqualTo("Queue entry " + FILE + " of process 1234 is not"
            + " in the Queue Manager of test/node1; nothing was read");
        assertThat(content.calls).isEmpty();
    }

    @Test
    void onlyNonErrorRowsWithoutQueueFileAreInvalidInputListingTheirStates() {
        rows.answer(new QueueEntries(List.of(), List.of(ProcessState.WAITING, ProcessState.OTHER),
            2));

        ToolError error = errorOf(() -> read(TEST.value(), PID, Optional.empty(),
            OptionalInt.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).isEqualTo("read_queue_entry reads only ERROR entries;"
            + " process 1234 on test/node1 has: WAITING (without queue file), OTHER (without"
            + " queue file)");
        assertThat(error.nextStep()).isEqualTo("Wait until the entry is in ERROR, or use"
            + " find_processes");
        assertThat(content.calls).isEmpty();
    }

    @Test
    void nonErrorRowsWithAndWithoutQueueFileAreListedTogether() {
        rows.answer(new QueueEntries(
            List.of(row(PID, "Module-0002(4712)@1234@0a1b2c3e", ProcessState.WAITING,
                "Waiting")),
            List.of(ProcessState.QUEUED), 2));

        ToolError error = errorOf(() -> read(TEST.value(), PID, Optional.empty(),
            OptionalInt.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).isEqualTo("read_queue_entry reads only ERROR entries;"
            + " process 1234 on test/node1 has: Waiting (Module-0002(4712)@1234@0a1b2c3e),"
            + " QUEUED (without queue file)");
        assertThat(content.calls).isEmpty();
    }

    // --- parts, port call, re-read --------------------------------------------------------------

    @Test
    void dataOver10MiBIsNotRequestedAndReportedTooLarge() {
        long size = QueueEntryBounds.MAX_DATA_BYTES + 1;
        rows.answer(entries(new QueueEntryRow(PID, Optional.empty(), ProcessState.ERROR,
            "Error", SINCE, "Workflow-0001", Optional.empty(), Optional.empty(), FILE, size,
            new TreeMap<>())));
        content.answer(new RawEntryContent(new PartResult.NotRequested<>("not requested"),
            new PartResult.Read<>(List.of())));

        RawEntryRead read = read(TEST.value(), PID, Optional.empty(), OptionalInt.empty());

        assertThat(content.calls).singleElement().satisfies(call ->
            assertThat(call.parts()).containsExactly(EntryPart.VARIABLES));
        assertThat(read.content().data()).isEqualTo(new PartResult.TooLarge<byte[]>(size));
        assertThat(read.content().variables()).isEqualTo(new PartResult.Read<>(List.of()));
    }

    @Test
    void dataOfExactly10MiBIsRequested() {
        rows.answer(entries(new QueueEntryRow(PID, Optional.empty(), ProcessState.ERROR,
            "Error", SINCE, "Workflow-0001", Optional.empty(), Optional.empty(), FILE,
            QueueEntryBounds.MAX_DATA_BYTES, new TreeMap<>())));
        content.answer(read());

        read(TEST.value(), PID, Optional.empty(), OptionalInt.empty());

        assertThat(content.calls).singleElement().satisfies(call ->
            assertThat(call.parts()).containsExactlyInAnyOrder(EntryPart.DATA,
                EntryPart.VARIABLES));
    }

    @Test
    void notFoundWithTheRowGoneIsNotFound() {
        rows.answer(entries(errorRow(FILE)));
        rows.answer(entries(row(PID, "Module-0002(4712)@1234@0a1b2c3e", ProcessState.WAITING,
            "Waiting")));
        content.answer(new RawEntryContent(new PartResult.NotFound<>(),
            new PartResult.NotFound<>()));

        ToolError error = errorOf(() -> read(TEST.value(), PID, Optional.empty(),
            OptionalInt.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(error.message()).isEqualTo("Queue entry Module-0001(4711)@1234@0a1b2c3d of"
            + " process 1234 left the Queue Manager of test/node1 (restarted, killed or deleted"
            + " meanwhile)");
        assertThat(error.nextStep()).isEqualTo("Look the instance up again with find_processes");
        assertThat(error.node()).contains(TEST);
        assertThat(rows.requests).containsExactly(PID, PID);
        assertThat(content.calls).hasSize(1);
    }

    @Test
    void notFoundWithTheRowStillThereIsAPartialRead() {
        rows.answer(entries(errorRow(FILE)));
        rows.answer(entries(errorRow(FILE)));
        RawEntryContent partial = new RawEntryContent(new PartResult.Read<>(new byte[] {'x'}),
            new PartResult.NotFound<>());
        content.answer(partial);

        RawEntryRead read = read(TEST.value(), PID, Optional.empty(), OptionalInt.empty());

        assertThat(read.content()).isSameAs(partial);
        assertThat(rows.requests).containsExactly(PID, PID);
    }

    @Test
    void emptyResultOfTheReReadIsNotFound() {
        rows.answer(entries(errorRow(FILE)));
        rows.answer(new QueueEntries(List.of(), 0));
        content.answer(new RawEntryContent(new PartResult.NotFound<>(),
            new PartResult.Read<>(List.of())));

        assertThat(errorOf(() -> read(TEST.value(), PID, Optional.empty(), OptionalInt.empty()))
            .code()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void noReReadWithoutNotFound() {
        rows.answer(entries(errorRow(FILE)));
        content.answer(new RawEntryContent(new PartResult.Failed<>("INUBIT reported an error"),
            new PartResult.TooLarge<>(QueueEntryBounds.MAX_DATA_BYTES + 1)));

        read(TEST.value(), PID, Optional.empty(), OptionalInt.empty());

        assertThat(rows.requests).containsExactly(PID);
    }

    /** Spec edge case: credentials not yet available — the lookup's error, nothing launched. */
    @Test
    void missingCredentialsFailWithTheLookupErrorAndNoContentRequest() {
        ToolError credentials = ToolError.of(ErrorCode.SECRET_UNRESOLVED,
            "The credentials of test/node1 are not available: INUBIT_ACME_TEST_PASSWORD is not"
                + " set", "c", "s").withNode(TEST);
        rows.fail(new ToolErrorException(credentials));

        ToolError error = errorOf(() -> read(TEST.value(), PID, Optional.empty(),
            OptionalInt.empty()));

        assertThat(error).isEqualTo(credentials);
        assertThat(content.calls).isEmpty();
    }

    @Test
    void contentFailureIsPassedThroughWithoutPartialContent() {
        rows.answer(entries(errorRow(FILE)));
        ToolError timeout = ToolError.of(ErrorCode.TIMEOUT, "The queue-entry helper did not"
            + " finish within cliTimeout (60 s)", "c", "s").withNode(TEST);
        content.fail(new ToolErrorException(timeout));

        assertThat(errorOf(() -> read(TEST.value(), PID, Optional.empty(), OptionalInt.empty())))
            .isEqualTo(timeout);
        assertThat(rows.requests).containsExactly(PID);
    }

    @Test
    void reReadFailureAfterNotFoundIsPassedThroughWithoutPartialContent() {
        rows.answer(entries(errorRow(FILE)));
        ToolError timeout = ToolError.of(ErrorCode.TIMEOUT, "The Queue Manager of test/node1 did"
            + " not answer within httpTimeout (30 s)", "c", "s").withNode(TEST);
        rows.fail(new ToolErrorException(timeout));
        content.answer(new RawEntryContent(new PartResult.Read<>(new byte[] {'x'}),
            new PartResult.NotFound<>()));

        assertThat(errorOf(() -> read(TEST.value(), PID, Optional.empty(), OptionalInt.empty())))
            .isEqualTo(timeout);
        assertThat(rows.requests).containsExactly(PID, PID);
        assertThat(content.calls).hasSize(1);
    }

    @Test
    void portsOfTheResolvedNodeAreUsed() {
        rows.answer(entries(errorRow(FILE)));
        content.answer(read());

        read(TEST2.value(), PID, Optional.empty(), OptionalInt.empty());

        assertThat(rows.nodes).containsExactly(TEST2);
        assertThat(content.calls).singleElement().satisfies(call -> {
            assertThat(call.node()).isEqualTo(TEST2);
            assertThat(call.ref().node()).isEqualTo(TEST2);
        });
    }

    // --- result shaping (T024) ----------------------------------------------------------------

    /** US1-1: metadata, the data stream as text and the variables, in the contract's shape. */
    @Test
    void us1_1_resultHasMetadataTextAndVariablesInTheContractShape() {
        QueueEntryResult result = shaped(new RawEntryContent(new PartResult.Read<>(utf8(ORDER)),
            new PartResult.Read<>(List.of(
                new RawVariable("ISFileSize", Optional.of("Long"), "772"),
                new RawVariable("var.order", Optional.of("XmlDocument"), ORDER)))));

        assertThat(json(result)).isEqualTo("{\"node\":\"test/node1\",\"entry\":{"
            + "\"processId\":\"1234\",\"globalProcessId\":\"0f0e0d0c-0000-4000-8000-000000000001\","
            + "\"workflow\":\"Workflow-0001\",\"module\":\"Module-0001\",\"moduleType\":\"Throw\","
            + "\"state\":\"ERROR\",\"rawState\":\"Error\",\"since\":\"2026-11-10T08:15:30Z\","
            + "\"fileName\":\"" + FILE + "\",\"sizeBytes\":772,"
            + "\"errorTexts\":{\"userDefined1\":\"ACME-ERROR\"}},"
            + "\"data\":{\"available\":true,\"kind\":\"text\",\"charset\":\"UTF-8\","
            + "\"sizeBytes\":" + ORDER.length() + ",\"totalChars\":" + ORDER.length()
            + ",\"returnedChars\":" + ORDER.length() + ",\"truncated\":false,"
            + "\"text\":\"" + ORDER + "\"},"
            + "\"variables\":{\"available\":true,\"totalCount\":2,\"returnedCount\":2,"
            + "\"truncated\":false,\"items\":["
            + "{\"name\":\"ISFileSize\",\"system\":true,\"type\":\"Long\",\"kind\":\"text\","
            + "\"value\":\"772\",\"masked\":false},"
            + "{\"name\":\"var.order\",\"system\":false,\"type\":\"XmlDocument\",\"kind\":\"text\","
            + "\"value\":\"" + ORDER + "\",\"masked\":false}]},"
            + "\"warnings\":[\"Read through an unsupported, undocumented INUBIT interface;"
            + " diagnosis only\"]}");
    }

    @Test
    void entryOmitsWhatTheRowDoesNotKnow() {
        rows.answer(entries(row(PID, FILE, ProcessState.ERROR, "Error")));
        content.answer(read());

        QueueEntryResult result = service.read(TEST.value(), PID, Optional.empty(),
            OptionalInt.empty());

        assertThat(json(result.entry())).isEqualTo("{\"processId\":\"1234\","
            + "\"workflow\":\"Workflow-0001\",\"module\":\"Module-0001\",\"state\":\"ERROR\","
            + "\"rawState\":\"Error\",\"since\":\"2026-11-10T08:15:30Z\",\"fileName\":\"" + FILE
            + "\",\"sizeBytes\":772,\"errorTexts\":{}}");
    }

    /** US1-2: cut at maxChars, marked, with the full size. */
    @Test
    void us1_2_textLongerThanMaxCharsIsCutAndMarked() {
        String text = "a".repeat(150) + "b".repeat(100);

        QueueEntryResult.Data data = shaped(data(utf8(text)), OptionalInt.of(150)).data();

        assertThat(data.text()).contains("a".repeat(150));
        assertThat(data.returnedChars()).contains(150);
        assertThat(data.totalChars()).contains(250);
        assertThat(data.sizeBytes()).contains(250L);
        assertThat(data.truncated()).contains(true);
    }

    @Test
    void textOf20001CharsIsCutAtTheDefault() {
        QueueEntryResult.Data data = shaped(data(utf8("t".repeat(20_001)))).data();

        assertThat(data.text().orElseThrow()).hasSize(20_000);
        assertThat(data.returnedChars()).contains(20_000);
        assertThat(data.totalChars()).contains(20_001);
        assertThat(data.truncated()).contains(true);
    }

    @Test
    void textOfExactlyMaxCharsIsNotTruncated() {
        QueueEntryResult.Data data = shaped(data(utf8("t".repeat(20_000)))).data();

        assertThat(data.returnedChars()).contains(20_000);
        assertThat(data.truncated()).contains(false);
    }

    @Test
    void cutNeverSplitsASurrogatePair() {
        String text = "x".repeat(9) + "\uD83D\uDE00" + "y";

        QueueEntryResult.Data data = shaped(data(utf8(text)), OptionalInt.of(10)).data();

        assertThat(data.text()).contains("x".repeat(9));
        assertThat(data.returnedChars()).contains(9);
        assertThat(data.totalChars()).contains(12);
        assertThat(data.truncated()).contains(true);
    }

    /** US1-3: binary content only as size, detected kind and hint. */
    @Test
    void us1_3_binaryZipIsReturnedAsSizeKindAndHintOnly() {
        byte[] zip = {0x50, 0x4B, 0x03, 0x04, 'a', 'b', 'c', 'd', 'e', 'f'};

        assertThat(json(shaped(data(zip)).data())).isEqualTo("{\"available\":true,"
            + "\"kind\":\"binary\",\"sizeBytes\":10,\"detected\":\"ZIP\","
            + "\"hint\":\"Binary content is not returned\"}");
    }

    @Test
    void binaryWithoutSignatureHasNoDetectedKind() {
        assertThat(json(shaped(data(new byte[] {'a', 0, 'b'})).data())).isEqualTo(
            "{\"available\":true,\"kind\":\"binary\",\"sizeBytes\":3,"
                + "\"hint\":\"Binary content is not returned\"}");
    }

    @Test
    void emptyDataStreamHasNoContentField() {
        assertThat(json(shaped(data(new byte[0])).data())).isEqualTo(
            "{\"available\":true,\"kind\":\"empty\",\"sizeBytes\":0}");
    }

    @Test
    void latin1TextIsDecodedAndItsCharsetNamed() {
        byte[] latin1 = "Grüße an Order-0001".getBytes(StandardCharsets.ISO_8859_1);

        QueueEntryResult.Data data = shaped(data(latin1)).data();

        assertThat(data.kind()).contains("text");
        assertThat(data.charset()).contains("ISO-8859-1");
        assertThat(data.text()).contains("Grüße an Order-0001");
        assertThat(data.sizeBytes()).contains((long) latin1.length);
    }

    /** US1-4: credential-named variables and an Authorization header among the variables. */
    @Test
    void us1_4_credentialNamedVariablesBecomePlaceholders() {
        String secret = TestSecrets.CREDENTIAL_PAYLOAD_VALUE;
        String basic = Base64.getEncoder().encodeToString(("acme:" + secret)
            .getBytes(StandardCharsets.UTF_8));

        List<QueueEntryResult.Variable> items = items(shaped(variables(
            variable("password", secret),
            variable("apiToken", secret),
            new RawVariable("Authorization", Optional.of("String"), "Basic " + basic),
            variable("X-Api-Key", secret),
            variable("ns:clientSecret", "PK\u0003\u0004" + secret),
            variable("headers", "Accept: text/xml\nAuthorization: Basic " + basic + "\n"))));

        assertThat(items).hasSize(6);
        assertThat(items.subList(0, 5)).allSatisfy(item -> {
            assertThat(item.masked()).isTrue();
            assertThat(item.kind()).isEqualTo("text");
            assertThat(item.value()).contains("${secret:variables." + item.name()
                .replace(':', '_') + "}");
            assertThat(item.sizeBytes()).isEmpty();
            assertThat(item.detected()).isEmpty();
        });
        assertThat(items.get(2).type()).contains("String");
        assertThat(items.get(5).masked()).isFalse();
        assertThat(items.get(5).value()).contains("Accept: text/xml\nAuthorization:"
            + " ${secret:authorization}\n");
        assertThat(json(items.get(1))).isEqualTo("{\"name\":\"apiToken\",\"system\":false,"
            + "\"kind\":\"text\",\"value\":\"${secret:variables.apiToken}\",\"masked\":true}");
        TestSecrets.assertNoLeak(json(items));
        assertThat(json(items)).doesNotContain(basic);
    }

    /** Fail-safe (FR-011): a term before the last {@code :} also makes a variable secret. */
    @Test
    void aVariableIsSecretIfItsFullNameContainsATerm() {
        String secret = TestSecrets.CREDENTIAL_PAYLOAD_VALUE;

        List<QueueEntryResult.Variable> items = items(shaped(variables(
            variable("token:id", secret),
            variable("password:old", secret),
            variable("order:id", "Order-0001"))));

        assertThat(items.subList(0, 2)).allSatisfy(item -> assertThat(item.masked()).isTrue());
        assertThat(items.get(0).value()).contains("${secret:variables.token_id}");
        assertThat(items.get(1).value()).contains("${secret:variables.password_old}");
        assertThat(items.get(2).masked()).isFalse();
        assertThat(items.get(2).value()).contains("Order-0001");
        TestSecrets.assertNoLeak(json(items));
    }

    /** US1-5: a configured credential in the data stream or a value is scrubbed. */
    @Test
    void us1_5_knownCredentialsAreScrubbed() {
        String password = TestSecrets.CONFIGURED_PASSWORD;
        RawEntryContent raw = new RawEntryContent(
            new PartResult.Read<>(utf8("<Login><User>acme</User><Pass>" + password
                + "</Pass></Login>")),
            new PartResult.Read<>(List.of(variable("lastCall", "acme/" + password + "@globex"),
                variable("note" + password, "x"))));

        QueueEntryResult result = shaped(raw);

        assertThat(result.data().text()).contains("<Login><User>acme</User><Pass>***</Pass>"
            + "</Login>");
        assertThat(items(result).get(0).value()).contains("acme/***@globex");
        assertThat(items(result).get(1).name()).isEqualTo("note***");
        TestSecrets.assertNoLeak(json(result));
    }

    /** US1-6: secret-named elements, attributes and JSON keys in the data stream. */
    @Test
    void us1_6_secretNamedValuesInTheDataStreamAreMasked() {
        String secret = TestSecrets.CREDENTIAL_PAYLOAD_VALUE;
        String soap = "<soap:Envelope><soap:Header><wsse:Security><wsse:Username>acme"
            + "</wsse:Username><wsse:Password>" + secret + "</wsse:Password>"
            + "</wsse:Security></soap:Header><soap:Body><Order-0001/></soap:Body>"
            + "</soap:Envelope>";

        assertThat(shaped(data(utf8(soap))).data().text()).contains("<soap:Envelope>"
            + "<soap:Header><wsse:Security><wsse:Username>acme</wsse:Username>"
            + "<wsse:Password>${secret:data.Password}</wsse:Password></wsse:Security>"
            + "</soap:Header><soap:Body><Order-0001/></soap:Body></soap:Envelope>");
        // the whole UsernameToken (a secret-named element) becomes one placeholder
        assertThat(shaped(data(utf8("<wsse:UsernameToken><wsse:Password>" + secret
            + "</wsse:Password></wsse:UsernameToken>"))).data().text())
            .contains("<wsse:UsernameToken>${secret:data.UsernameToken}</wsse:UsernameToken>");

        String jsonText = "{\"order\": \"Order-0001\", \"password\": \"" + secret + "\"}";
        assertThat(shaped(data(utf8(jsonText))).data().text()).contains("{\"order\":"
            + " \"Order-0001\", \"password\": \"${secret:data.password}\"}");
    }

    /** Masking runs on the full text before the cut: no piece of a secret survives the cut. */
    @Test
    void dataIsMaskedBeforeItIsCut() {
        String token = "abcdefghijklmnopqrstuvwxyz012345";
        // a cut first would leave 12 characters of the token, below rule 7's minimum of 16
        String text = "x".repeat(80) + " Bearer " + token;

        String returned = shaped(data(utf8(text)), OptionalInt.of(100)).data().text()
            .orElseThrow();

        assertThat(returned).hasSize(100).startsWith("x".repeat(80) + " Bearer ${secret:aut")
            .doesNotContain("abcd");
    }

    @Test
    void knownCredentialSplitAcrossTheCutPositionIsStillScrubbed() {
        String password = TestSecrets.CONFIGURED_PASSWORD;
        String text = "x".repeat(95) + password + "tail";

        QueueEntryResult result = shaped(new RawEntryContent(new PartResult.Read<>(utf8(text)),
            new PartResult.Read<>(List.of(variable("v", "y".repeat(1_998) + password)))),
            OptionalInt.of(100));

        assertThat(result.data().text()).contains("x".repeat(95) + "***ta");
        assertThat(items(result).get(0).value()).contains("y".repeat(1_998) + "**");
        assertThat(json(result)).doesNotContain(password.substring(0, 4));
    }

    /** US1-8: variables missing while the data stream is returned. */
    @Test
    void us1_8_missingVariablesAreUnavailableWithTheReason() {
        rows.answer(entries(errorRow(FILE)));
        rows.answer(entries(errorRow(FILE)));
        content.answer(new RawEntryContent(new PartResult.Read<>(utf8(ORDER)),
            new PartResult.NotFound<>()));

        QueueEntryResult result = service.read(TEST.value(), PID, Optional.empty(),
            OptionalInt.empty());

        assertThat(result.data().text()).contains(ORDER);
        assertThat(json(result.variables())).isEqualTo("{\"available\":false,"
            + "\"reason\":\"INUBIT did not find the content of this entry\"}");
        assertThat(result.entry().fileName()).isEqualTo(FILE);
    }

    @Test
    void unavailablePartsCarryTheirReason() {
        String format = "unexpected format; the unsupported INUBIT interface may have changed";
        QueueEntryResult result = shaped(new RawEntryContent(
            new PartResult.Failed<>("INUBIT reported an error: Unable to download "
                + TestSecrets.CONFIGURED_PASSWORD),
            new PartResult.Failed<>(format)));

        assertThat(json(result.data())).isEqualTo("{\"available\":false,"
            + "\"reason\":\"INUBIT reported an error: Unable to download ***\"}");
        assertThat(json(result.variables())).isEqualTo("{\"available\":false,"
            + "\"reason\":\"" + format + "\"}");

        result = shaped(new RawEntryContent(new PartResult.NotFound<>(),
            new PartResult.TooLarge<>(11_000_000)), rows -> rows.answer(entries(errorRow(FILE))));
        assertThat(result.data().reason()).contains("INUBIT did not find the content of this"
            + " entry");
        assertThat(result.variables().reason()).contains("too large (11000000 bytes; limit"
            + " 10 MiB)");
    }

    @Test
    void dataLargerThan10MiBIsTooLargeWithTheRowSize() {
        QueueEntryRow big = new QueueEntryRow(PID, Optional.empty(), ProcessState.ERROR, "Error",
            SINCE, "Workflow-0001", Optional.empty(), Optional.empty(), FILE, 12_582_912,
            new TreeMap<>());
        rows.answer(entries(big));
        content.answer(new RawEntryContent(new PartResult.NotRequested<>("not requested"),
            new PartResult.Read<>(List.of(variable("v", "1")))));

        QueueEntryResult result = service.read(TEST.value(), PID, Optional.empty(),
            OptionalInt.empty());

        assertThat(json(result.data())).isEqualTo("{\"available\":false,"
            + "\"reason\":\"too large (12582912 bytes; limit 10 MiB)\"}");
        assertThat(result.variables().returnedCount()).contains(1);
    }

    @Test
    void errorTextsOfTheRowAreScrubbed() {
        rows.answer(entries(new QueueEntryRow(PID, Optional.empty(), ProcessState.ERROR,
            "Error", SINCE, "Workflow-0001", Optional.empty(), Optional.empty(), FILE, 1,
            new TreeMap<>(Map.of("userDefined2", "login acme/"
                + TestSecrets.CONFIGURED_PASSWORD)))));
        content.answer(read());

        QueueEntryResult result = service.read(TEST.value(), PID, Optional.empty(),
            OptionalInt.empty());

        assertThat(result.entry().errorTexts()).containsExactly(
            Map.entry("userDefined2", "login acme/***"));
    }

    /** US1-9: 250 variables → the first 200 with a truncation marker naming the total. */
    @Test
    void us1_9_atMost200VariablesAreReturned() {
        RawVariable[] many = IntStream.range(0, 250)
            .mapToObj(i -> variable("var" + i, "v" + i)).toArray(RawVariable[]::new);

        QueueEntryResult.Variables variables = shaped(variables(many)).variables();

        assertThat(variables.totalCount()).contains(250);
        assertThat(variables.returnedCount()).contains(200);
        assertThat(variables.truncated()).contains(true);
        assertThat(variables.items().orElseThrow()).hasSize(200)
            .extracting(QueueEntryResult.Variable::name)
            .startsWith("var0", "var1").endsWith("var199");
    }

    @Test
    void systemVariablesAreMarked() {
        List<QueueEntryResult.Variable> items = items(shaped(variables(
            variable("ISFileSize", "772"), variable("ISOverwriteSubWorkflowPriority", "false"),
            variable("_hidden.state", "1"), variable("isFileSize", "772"),
            variable("ISFileSizeX", "772"))));

        assertThat(items).extracting(QueueEntryResult.Variable::system)
            .containsExactly(true, true, true, false, false);
    }

    @Test
    void variableNamesAreCutAt200WithoutSplittingASurrogatePair() {
        List<QueueEntryResult.Variable> items = items(shaped(variables(
            variable("n".repeat(201), "1"), variable("m".repeat(199) + "\uD83D\uDE00", "2"),
            variable("k".repeat(200), "3"))));

        assertThat(items.get(0).name()).isEqualTo("n".repeat(200));
        assertThat(items.get(0).nameTruncated()).contains(true);
        assertThat(items.get(1).name()).isEqualTo("m".repeat(199));
        assertThat(items.get(1).nameTruncated()).contains(true);
        assertThat(items.get(2).name()).isEqualTo("k".repeat(200));
        assertThat(items.get(2).nameTruncated()).isEmpty();
    }

    @Test
    void eachValueIsCutAt2000Characters() {
        List<QueueEntryResult.Variable> items = items(shaped(variables(
            variable("long", "l".repeat(2_500)), variable("exact", "e".repeat(2_000)))));

        assertThat(items.get(0).value()).contains("l".repeat(2_000));
        assertThat(items.get(0).truncated()).contains(true);
        assertThat(items.get(0).totalChars()).contains(2_500);
        assertThat(items.get(1).value()).contains("e".repeat(2_000));
        assertThat(items.get(1).truncated()).isEmpty();
        assertThat(items.get(1).totalChars()).isEmpty();
    }

    @Test
    void allValuesTogetherAreBoundedAt40000Characters() {
        RawVariable[] values = IntStream.range(0, 23)
            .mapToObj(i -> variable("v" + i, "a".repeat(1_900))).toArray(RawVariable[]::new);

        List<QueueEntryResult.Variable> items = items(shaped(variables(values)));

        assertThat(items.subList(0, 21)).allSatisfy(item -> {
            assertThat(item.value()).contains("a".repeat(1_900));
            assertThat(item.truncated()).isEmpty();
        });
        assertThat(items.get(21).value()).contains("a".repeat(100));
        assertThat(items.get(21).truncated()).contains(true);
        assertThat(items.get(21).totalChars()).contains(1_900);
        assertThat(items.get(22).value()).contains("");
        assertThat(items.get(22).truncated()).contains(true);
        assertThat(items.get(22).totalChars()).contains(1_900);
        assertThat(items.stream().mapToInt(item -> item.value().orElse("").length()).sum())
            .isEqualTo(40_000);
    }

    @Test
    void valuesAreMaskedBeforeTheyAreBounded() {
        String token = "abcdefghijklmnopqrstuvwxyz012345";
        List<QueueEntryResult.Variable> items = items(shaped(variables(
            variable("cut", "z".repeat(1_990) + " Bearer " + token),
            new RawVariable("var.config", Optional.of("XmlDocument"), "<Config><password>"
                + "s".repeat(3_000) + "</password></Config>"))));

        assertThat(items.get(0).value().orElseThrow()).hasSize(2_000).doesNotContain("abcd");
        assertThat(items.get(1).value()).contains("<Config><password>${secret:data.password}"
            + "</password></Config>");
        assertThat(items.get(1).truncated()).isEmpty();
        assertThat(items.get(1).masked()).isFalse();
    }

    @Test
    void binaryAndEmptyValuesAreShownWithoutContent() {
        List<QueueEntryResult.Variable> items = items(shaped(variables(
            variable("zipped", "PK\u0003\u0004rest"), variable("raw", "a\u0000b"),
            variable("empty", ""))));

        assertThat(json(items.get(0))).isEqualTo("{\"name\":\"zipped\",\"system\":false,"
            + "\"kind\":\"binary\",\"sizeBytes\":8,\"detected\":\"ZIP\",\"masked\":false}");
        assertThat(json(items.get(1))).isEqualTo("{\"name\":\"raw\",\"system\":false,"
            + "\"kind\":\"binary\",\"sizeBytes\":3,\"masked\":false}");
        assertThat(json(items.get(2))).isEqualTo("{\"name\":\"empty\",\"system\":false,"
            + "\"kind\":\"empty\",\"sizeBytes\":0,\"masked\":false}");
    }

    @Test
    void warningsAreTheFixedWarningThenTheGateWarnings() {
        QueueEntryResult result = service.result(new RawEntryRead(TEST, errorRow(FILE), read(),
            20_000, List.of("INUBIT 8.1.99 on test/node1 is not verified")));

        assertThat(result.warnings()).containsExactly("Read through an unsupported,"
            + " undocumented INUBIT interface; diagnosis only",
            "INUBIT 8.1.99 on test/node1 is not verified");
    }

    /** SC-003: secrets in every place of the content never reach the result. */
    @Test
    void resultDoesNotLeakSecrets() {
        String password = TestSecrets.CONFIGURED_PASSWORD;
        String payload = TestSecrets.CREDENTIAL_PAYLOAD_VALUE;
        String trust = TestSecrets.TRUST_STORE_PASSWORD;
        String basic = Base64.getEncoder().encodeToString(("acme:" + payload)
            .getBytes(StandardCharsets.UTF_8));
        String text = "<Envelope><Password>" + payload + "</Password>"
            + "<Config keystorePassword=\"" + trust + "\" user=\"acme\"/>"
            + "<Body>{\"apiKey\": \"" + payload + "\", \"order\": \"Order-0001\"}</Body>"
            + "<Url>https://globex.example.test/x?token=" + payload + "&amp;a=1</Url>"
            + "<Login>" + password + "</Login></Envelope>\n"
            + "Authorization: Basic " + basic + "\nX-Api-Key: " + payload + "\n"
            + "Cookie: session=" + payload + "\n";
        RawEntryContent raw = new RawEntryContent(new PartResult.Read<>(utf8(text)),
            new PartResult.Read<>(List.of(variable("password", payload),
                variable("trustStorePassword", trust), variable("body", text),
                variable("login", password))));

        for (int maxChars : new int[] {1, 25, 60, 100, 1_000, 100_000}) {
            QueueEntryResult result = shaped(raw, OptionalInt.of(maxChars));
            String json = json(result);
            TestSecrets.assertNoLeak(json);
            assertThat(json).doesNotContain(basic);
        }
    }

    private QueueEntryResult shaped(RawEntryContent raw) {
        return shaped(raw, OptionalInt.empty());
    }

    private QueueEntryResult shaped(RawEntryContent raw, OptionalInt maxChars) {
        rows.answer(entries(errorRow(FILE)));
        content.answer(raw);
        return service.read(TEST.value(), PID, Optional.empty(), maxChars);
    }

    /** As {@link #shaped(RawEntryContent)}, with {@code more} rows scripted for a re-read. */
    private QueueEntryResult shaped(RawEntryContent raw,
        Consumer<FakeRows> more) {
        rows.answer(entries(errorRow(FILE)));
        more.accept(rows);
        content.answer(raw);
        return service.read(TEST.value(), PID, Optional.empty(), OptionalInt.empty());
    }

    private static RawEntryContent data(byte[] bytes) {
        return new RawEntryContent(new PartResult.Read<>(bytes),
            new PartResult.Read<>(List.<RawVariable>of()));
    }

    private static RawEntryContent variables(RawVariable... variables) {
        return new RawEntryContent(new PartResult.Read<>(new byte[0]),
            new PartResult.Read<>(List.of(variables)));
    }

    private static RawVariable variable(String name, String value) {
        return new RawVariable(name, Optional.empty(), value);
    }

    private static List<QueueEntryResult.Variable> items(QueueEntryResult result) {
        return result.variables().items().orElseThrow();
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String json(Object value) {
        return ResultJson.mapper().writeValueAsString(value);
    }

    // --- leak check ---------------------------------------------------------------------------

    /** SC-003: no message names the error texts of a row, which may quote the payload. */
    @Test
    void messagesDoNotLeakSecrets() {
        TreeMap<String, String> texts = new TreeMap<>(Map.of(
            "userDefined1", "password=" + TestSecrets.CONFIGURED_PASSWORD,
            "userDefined2", "trust " + TestSecrets.TRUST_STORE_PASSWORD,
            "userDefined3", "apiToken " + TestSecrets.CREDENTIAL_PAYLOAD_VALUE));
        QueueEntryRow first = new QueueEntryRow(PID, Optional.empty(), ProcessState.ERROR,
            "Error", SINCE, "Workflow-0001", Optional.of("Module-0001"), Optional.of("Throw"),
            FILE, 772, texts);
        QueueEntryRow second = new QueueEntryRow(PID, Optional.empty(), ProcessState.ERROR,
            "Error", SINCE.minusSeconds(1), "Workflow-0002", Optional.of("Module-0003"),
            Optional.empty(), "Module-0003(4713)@1234@0a1b2c3f", 10, texts);
        QueueEntryRow waiting = new QueueEntryRow(PID, Optional.empty(), ProcessState.WAITING,
            "Waiting", SINCE, "Workflow-0001", Optional.empty(), Optional.empty(),
            "Module-0002(4712)@1234@0a1b2c3e", 1, texts);

        rows.answer(entries(first, second));
        errorOf(() -> read(TEST.value(), PID, Optional.empty(), OptionalInt.empty()));
        rows.answer(entries(waiting));
        errorOf(() -> read(TEST.value(), PID, Optional.empty(), OptionalInt.empty()));
        rows.answer(entries(first, waiting));
        errorOf(() -> read(TEST.value(), PID, Optional.of(waiting.fileName()),
            OptionalInt.empty()));
        rows.answer(entries(first));
        rows.answer(new QueueEntries(List.of(), 0));
        content.answer(new RawEntryContent(new PartResult.NotFound<>(),
            new PartResult.NotFound<>()));
        errorOf(() -> read(TEST.value(), PID, Optional.empty(), OptionalInt.empty()));
        errorOf(() -> read(PROD.value(), PID, Optional.empty(), OptionalInt.empty()));
        rows.answer(entries(first, waiting));
        errorOf(() -> read(TEST.value(), PID, Optional.of("Module-0009(1)@1234@0a1b2c3d"),
            OptionalInt.empty()));
        rows.answer(new QueueEntries(List.of(first, second), 250));
        errorOf(() -> read(TEST.value(), PID, Optional.empty(), OptionalInt.empty()));
        rows.answer(new QueueEntries(List.of(waiting), 250));
        errorOf(() -> read(TEST.value(), PID, Optional.empty(), OptionalInt.empty()));
        rows.answer(new QueueEntries(List.of(first, waiting), 250));
        errorOf(() -> read(TEST.value(), PID, Optional.of("Module-0009(1)@1234@0a1b2c3d"),
            OptionalInt.empty()));

        assertThat(errors).hasSize(9);
        assertThat(errors.subList(6, 9)).allSatisfy(error ->
            assertThat(error.message()).contains("rows were read"));
        errors.forEach(error -> TestSecrets.assertNoLeak(error.code() + " " + error.message()
            + " " + error.likelyCause() + " " + error.nextStep() + " "
            + error.excerpt().orElse("")));
    }

    // --- helpers ------------------------------------------------------------------------------

    private RawEntryRead read(String target, String processId, Optional<String> fileName,
        OptionalInt maxChars) {
        return service.readRaw(target, processId, fileName, maxChars);
    }

    private ToolError errorOf(Executable call) {
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class, () -> {
            call.execute();
        });
        assertThat(exception).as("a ToolErrorException").isNotNull();
        errors.add(exception.error());
        return exception.error();
    }

    private void assertNothingSent() {
        assertThat(detection.requests()).isEmpty();
        assertThat(rows.requests).isEmpty();
        assertThat(content.calls).isEmpty();
    }

    private static QueueEntries entries(QueueEntryRow... rows) {
        return new QueueEntries(List.of(rows), rows.length);
    }

    private static QueueEntryRow errorRow(String fileName) {
        return new QueueEntryRow(PID, Optional.of("0f0e0d0c-0000-4000-8000-000000000001"),
            ProcessState.ERROR, "Error", SINCE, "Workflow-0001", Optional.of("Module-0001"),
            Optional.of("Throw"), fileName, 772,
            new TreeMap<>(Map.of("userDefined1", "ACME-ERROR")));
    }

    private static QueueEntryRow row(String processId, String fileName, ProcessState state,
        String rawState) {
        return new QueueEntryRow(processId, Optional.empty(), state, rawState, SINCE,
            "Workflow-0001", Optional.of("Module-0001"), Optional.empty(), fileName, 772,
            new TreeMap<>());
    }

    private static RawEntryContent read() {
        return new RawEntryContent(new PartResult.Read<>(new byte[0]),
            new PartResult.Read<>(List.<RawVariable>of()));
    }

    /** {@link ProcessQueryPort#queueEntries} answering the scripted rows in order. */
    private static final class FakeRows {

        private final Deque<Supplier<QueueEntries>> answers = new ArrayDeque<>();
        private final List<String> requests = new ArrayList<>();
        private final List<NodeId> nodes = new ArrayList<>();

        void answer(QueueEntries entries) {
            answers.add(() -> entries);
        }

        void fail(ToolErrorException failure) {
            answers.add(() -> {
                throw failure;
            });
        }

        ProcessQueryPort port(NodeId node) {
            return new ProcessQueryPort() {
                @Override
                public void validate(ProcessQuery query) {
                    throw new AssertionError("only queueEntries is expected");
                }

                @Override
                public Page<ProcessInstance> find(ProcessQuery query) {
                    throw new AssertionError("only queueEntries is expected");
                }

                @Override
                public ProcessRows findByProcessId(String processId, Instant now,
                    Duration hangingThreshold) {
                    throw new AssertionError("only queueEntries is expected");
                }

                @Override
                public QueueEntries queueEntries(String processId) {
                    requests.add(processId);
                    nodes.add(node);
                    Supplier<QueueEntries> answer = answers.poll();
                    if (answer == null) {
                        throw new AssertionError("no rows scripted");
                    }
                    return answer.get();
                }
            };
        }
    }

    private record ContentCall(NodeId node, QueueEntryRef ref, InubitVersion version,
        Set<EntryPart> parts) {
    }

    /** {@link QueueEntryContentPort} recording its calls and answering the scripted content. */
    private static final class FakeContent {

        private final List<ContentCall> calls = new ArrayList<>();
        private Supplier<RawEntryContent> answer = () -> {
            throw new AssertionError("no content scripted");
        };

        void answer(RawEntryContent content) {
            answer = () -> content;
        }

        void fail(ToolErrorException failure) {
            answer = () -> {
                throw failure;
            };
        }

        QueueEntryContentPort port(NodeId node) {
            return (ref, version, parts) -> {
                calls.add(new ContentCall(node, ref, version, Set.copyOf(parts)));
                return answer.get();
            };
        }
    }
}
