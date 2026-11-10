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
import de.dadecker.inubit.mcp.domain.model.QueueEntryRow;
import de.dadecker.inubit.mcp.domain.model.RawEntryContent;
import de.dadecker.inubit.mcp.domain.model.RawEntryContent.PartResult;
import de.dadecker.inubit.mcp.domain.model.RawVariable;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.domain.port.QueueEntryContentPort;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
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
 * after "not found" (contracts/mcp-tools-delta.md "Errors"). Fakes of
 * {@link ProcessQueryPort#queueEntries} and {@link QueueEntryContentPort}; no WireMock, no
 * process.
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

    private final QueueEntryService service = new QueueEntryService(
        new TargetResolver(List.of(TEST, TEST2, PROD, LEGACY)),
        node -> !node.group().value().equals("prod"),
        FakeVersionDetection.gateWithoutClients(versions, detection,
            CapabilityMatrix.production()),
        node -> rows.port(node), node -> content.port(node));

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
            node -> rows.port(node), node -> content.port(node));

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
    void nineZeroIsRefusedAsNotYetVerified() {
        FakeVersionDetection nineZero = new FakeVersionDetection(versions)
            .answer(TEST, "9.0.7");
        QueueEntryService service90 = new QueueEntryService(new TargetResolver(List.of(TEST)),
            node -> true, FakeVersionDetection.gateWithoutClients(versions, nineZero,
                CapabilityMatrix.production()),
            node -> rows.port(node), node -> content.port(node));

        ToolError error = errorOf(() -> service90.readRaw(TEST.value(), PID, Optional.empty(),
            OptionalInt.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.message()).contains("not yet verified");
        assertThat(rows.requests).isEmpty();
        assertThat(content.calls).isEmpty();
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
