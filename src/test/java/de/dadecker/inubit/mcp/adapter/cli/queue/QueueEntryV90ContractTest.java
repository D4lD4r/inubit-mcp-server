package de.dadecker.inubit.mcp.adapter.cli.queue;

import static de.dadecker.inubit.mcp.adapter.cli.queue.QueueEntryVersionProfile.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.tuple;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher.Answer;
import de.dadecker.inubit.mcp.adapter.rest.v81.QueueEntryRowFixtures;
import de.dadecker.inubit.mcp.application.NodeVersions;
import de.dadecker.inubit.mcp.application.QueueEntryService;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.domain.model.ProcessRows;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.domain.model.QueueEntries;
import de.dadecker.inubit.mcp.domain.model.QueueEntryResult;
import de.dadecker.inubit.mcp.domain.model.QueueEntryResult.Data;
import de.dadecker.inubit.mcp.domain.model.QueueEntryResult.Variable;
import de.dadecker.inubit.mcp.domain.model.QueueEntryRow;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.infra.ResultJson;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T032, T038 (feature 020, FR-019, US3-2, SC-001, research D-20): the INUBIT 9.0 contract of
 * {@code read_queue_entry} through {@link CapabilityMatrix#production()}, which supports 9.0 since
 * the recorded {@code v9_0/error_entry} exists. The real {@link QueueEntryService} and
 * {@link CliQueueEntryAdapter} run the {@code REST_90} path: a fake launcher replays
 * {@code <case>.frames}/{@code <case>.exit} with the request's nonce and writes the case's
 * {@code .data}/{@code .wfsp} to the requested paths. The recorded entry is read in full; INUBIT's
 * "not found" (recorded {@code not_found_file}, reconstructed {@code missing_workflow}) makes the
 * service read the row again (D-5): still there → partial result, gone → {@code NOT_FOUND}. No
 * process, no network.
 */
@Timeout(60)
class QueueEntryV90ContractTest {

    /** The helper cases this test runs; exactly the {@code v9_0/*.frames} on disk. */
    static final List<String> CASES = List.of("error_entry", "missing_workflow",
        "not_found_file");

    /** The variables of the recorded {@code error_entry}, in the order INUBIT stored them. */
    private static final List<String> ERROR_ENTRY_VARIABLES = List.of("_hidden.ISTestClientUID",
        "ISFileSize", "ISCurrentTime", "ISWorkflowName", "ISModuleName", "ISModuleId",
        "ISProcessId", "ISUserName", "ISServerName", "ISNodeName", "ISGlobalProcessId");

    private static final Path DIRECTORY = Path.of("src/test/resources/fixtures/queue-entry/v9_0");
    private static final String RESOURCES = "/fixtures/queue-entry/v9_0/";
    private static final String FRAMES = ".frames";
    private static final String FIXTURE_NONCE = "0123456789abcdef0123456789abcdef";
    private static final NodeId NODE = NodeId.parse("test/n90");
    private static final String PID = "100000001";
    /** The file name the {@code not_found_file} frames name. */
    private static final String FILE =
        "module-0001(1001)@100000001@00000000-0a00-0000-0000-000000000001_1790861373736";
    private static final String NOT_FOUND = "INUBIT did not find the content of this entry";
    /** INUBIT's own texts in the frames; they never reach a result, an error or the log. */
    private static final List<String> INUBIT_TEXTS = List.of("Unable to download",
        "MissingWorkflowWithName", "IOException", "InubitException", "OWNERS/workflow");

    @TempDir
    Path root;

    private final NodeVersions versions = new NodeVersions(Clock.systemUTC(),
        node -> VersionSetting.AUTO, node -> Duration.ofMinutes(10));
    private final FakeVersionDetection detection = new FakeVersionDetection(versions)
        .answer(NODE, "9.0.10");
    private final SecretScrubber scrubber = new SecretScrubber();
    private final Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger("de.dadecker.inubit");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final AtomicInteger rowReads = new AtomicInteger();
    private final List<Map<String, String>> requests = new CopyOnWriteArrayList<>();
    private QueueEntryVersionProfile profile;
    private Level previousLevel;

    @BeforeEach
    void setUp() throws IOException {
        scrubber.register(TestSecrets.CONFIGURED_PASSWORD);
        profile = new QueueEntryVersionProfile(root);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
        appender.stop();
        profile.close();
        for (ILoggingEvent event : appender.list) {
            assertNoLeak(event.getFormattedMessage());
        }
    }

    @Test
    void theCasesAreExactlyTheHelperCasesOnDisk() throws IOException {
        TreeSet<String> onDisk;
        try (Stream<Path> files = Files.list(DIRECTORY)) {
            onDisk = files.map(file -> file.getFileName().toString())
                .filter(name -> name.endsWith(FRAMES))
                .map(name -> name.substring(0, name.length() - FRAMES.length()))
                .collect(Collectors.toCollection(TreeSet::new));
        }

        assertThat(onDisk).containsExactlyInAnyOrderElementsOf(CASES);
    }

    /** US3-2, SC-001: the recorded 9.0.10 ERROR entry through the production declaration. */
    @Test
    void theRecordedErrorEntryHasItsMetadataTextAndAllVariablesUnmasked() {
        byte[] answer = fixture("error_entry.rows.json");
        QueueEntryRow row = QueueEntryRowFixtures.queueEntries(NODE, answer).rows().get(0);
        FakeProcessLauncher launcher = replaying("error_entry");

        QueueEntryResult result = service(launcher, new RecordedRows(answer))
            .read(NODE.value(), row.processId(), Optional.empty(), OptionalInt.empty());

        String json = ResultJson.mapper().writeValueAsString(result);
        TestSecrets.assertNoLeak(json);
        assertThat(json).doesNotContain("${secret:");
        byte[] bytes = fixture("error_entry.data");
        QueueEntryResult.Entry entry = result.entry();
        assertThat(result.node()).isEqualTo(NODE);
        assertThat(entry.processId()).isEqualTo("100000090");
        assertThat(entry.globalProcessId()).contains("100000090");
        assertThat(entry.fileName()).isEqualTo(row.fileName());
        assertThat(entry.workflow()).isEqualTo("Workflow-0001");
        assertThat(entry.module()).contains("Workflow-0001(91)");
        assertThat(entry.moduleType()).contains("AS2 Connector");
        assertThat(entry.state()).isEqualTo(ProcessState.ERROR);
        assertThat(entry.rawState()).isEqualTo("Error");
        assertThat(entry.since()).isEqualTo(Instant.ofEpochMilli(1_790_950_000_116L));
        assertThat(entry.sizeBytes()).isEqualTo(bytes.length);
        assertThat(entry.errorTexts()).containsExactlyInAnyOrderEntriesOf(Map.of(
            "userDefined1", "XsdException.ErrorModuleInputValidation"));
        assertThat(result.warnings()).first().isEqualTo(QueueEntryResult.WARNING);
        String stored = new String(bytes, StandardCharsets.UTF_8);
        Data data = result.data();
        assertThat(data.kind()).contains("text");
        assertThat(data.charset()).contains("UTF-8");
        assertThat(data.sizeBytes()).contains((long) bytes.length);
        assertThat(data.totalChars()).contains(stored.length());
        assertThat(data.truncated()).contains(false);
        assertThat(data.text()).as("no secret-named value in the recording: nothing masked")
            .contains(stored);
        QueueEntryResult.Variables variables = result.variables();
        assertThat(variables.totalCount()).contains(ERROR_ENTRY_VARIABLES.size());
        assertThat(variables.returnedCount()).contains(ERROR_ENTRY_VARIABLES.size());
        assertThat(variables.truncated()).contains(false);
        List<Variable> items = variables.items().orElseThrow();
        assertThat(items).extracting(Variable::name)
            .containsExactlyElementsOf(ERROR_ENTRY_VARIABLES);
        assertThat(items).allSatisfy(item -> {
            assertThat(item.system()).as(item.name()).isTrue();
            assertThat(item.masked()).as(item.name()).isFalse();
        });
        assertThat(items).filteredOn(item -> item.type().isPresent())
            .extracting(Variable::name, item -> item.type().orElseThrow())
            .containsExactly(tuple("ISCurrentTime", "Long"));
        Map<String, Variable> byName = items.stream()
            .collect(Collectors.toMap(Variable::name, item -> item));
        assertThat(byName.get("ISFileSize").value()).contains(String.valueOf(bytes.length));
        assertThat(byName.get("ISProcessId").value()).contains("100000090");
        assertThat(byName.get("ISWorkflowName").value()).contains("Workflow-0001");
        assertThat(byName.get("ISServerName").value()).contains("inubit-qa-2.example.test");
        assertThat(detection.requests()).containsExactly(NODE);
        assertThat(rowReads).as("one queueLog lookup").hasValue(1);
        assertRestRequest(launcher, row.fileName());
        assertThat(lastLogLine()).endsWith("outcome OK");
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
            .as("FR-018: no content in the log")
            .noneMatch(message -> message.contains("catalogProduct")
                || message.contains("inubit-qa-2.example.test"));
    }

    @ParameterizedTest
    // the not-found subset of CASES
    @ValueSource(strings = {"missing_workflow", "not_found_file"})
    void notFoundWhileTheRowStaysIsAPartialResultWithBothPartsUnavailable(String name) {
        FakeProcessLauncher launcher = replaying(name);

        QueueEntryResult result = service(launcher, rows(true)).read(NODE.value(), PID,
            Optional.empty(), OptionalInt.empty());

        String json = ResultJson.mapper().writeValueAsString(result);
        assertNoLeak(json);
        assertThat(result.node()).isEqualTo(NODE);
        assertThat(result.entry().processId()).isEqualTo(PID);
        assertThat(result.entry().fileName()).isEqualTo(FILE);
        assertThat(result.entry().workflow()).isEqualTo("Workflow-0001");
        assertThat(result.entry().state()).isEqualTo(ProcessState.ERROR);
        assertThat(result.warnings()).first().isEqualTo(QueueEntryResult.WARNING);
        assertThat(result.data().available()).isFalse();
        assertThat(result.data().reason()).contains(NOT_FOUND);
        assertThat(result.data().text()).isEmpty();
        assertThat(result.variables().available()).isFalse();
        assertThat(result.variables().reason()).contains(NOT_FOUND);
        assertThat(result.variables().items()).isEmpty();
        assertThat(rowReads).as("the rows are read again after not found").hasValue(2);
        assertRestRequest(launcher, FILE);
        assertThat(lastLogLine()).endsWith("outcome PARTIAL");
    }

    @ParameterizedTest
    // the not-found subset of CASES
    @ValueSource(strings = {"missing_workflow", "not_found_file"})
    void notFoundAfterTheRowLeftIsNotFound(String name) {
        FakeProcessLauncher launcher = replaying(name);

        ToolError error = errorOf(() -> service(launcher, rows(false))
            .read(NODE.value(), PID, Optional.empty(), OptionalInt.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(error.node()).contains(NODE);
        assertThat(error.message()).isEqualTo("Queue entry " + FILE + " of process " + PID
            + " left the Queue Manager of " + NODE + " (restarted, killed or deleted meanwhile)");
        assertThat(rowReads).as("the rows are read again after not found").hasValue(2);
        assertRestRequest(launcher, FILE);
        assertThat(lastLogLine()).endsWith("outcome NOT_FOUND");
    }

    // --- the run ------------------------------------------------------------------------------

    /**
     * {@code read_queue_entry} for {@link #NODE} (detected as 9.0.10) through the gate of
     * {@link CapabilityMatrix#production()}, the queue rows answered by {@code rows}.
     */
    private QueueEntryService service(FakeProcessLauncher launcher, ProcessQueryPort rows) {
        CliQueueEntryAdapter adapter = profile.adapter(
            profile.node(NODE.value(), VersionSetting.AUTO).build(), launcher);
        return new QueueEntryService(new TargetResolver(List.of(NODE)), node -> true,
            FakeVersionDetection.gateWithoutClients(versions, detection,
                CapabilityMatrix.production()),
            node -> rows, node -> adapter, scrubber::scrub);
    }

    /**
     * A helper replaying the case's frames and exit code with the request's nonce; it writes the
     * case's {@code .data} (if requested) and {@code .wfsp}, where they exist, to the request's
     * output paths.
     */
    private FakeProcessLauncher replaying(String name) {
        String frames = new String(fixture(name + FRAMES), StandardCharsets.UTF_8);
        int exit = Integer.parseInt(new String(fixture(name + ".exit"),
            StandardCharsets.UTF_8).strip());
        return FakeProcessLauncher.of("", "", 0).responding(stdin -> {
            Map<String, String> fields = request(stdin);
            requests.add(fields);
            if (!fields.get("dataOut").isEmpty()) {
                optionalFixture(name + ".data").ifPresent(data -> write(fields.get("dataOut"),
                    data));
            }
            optionalFixture(name + ".wfsp").ifPresent(wfsp -> write(fields.get("variablesOut"),
                wfsp));
            return new Answer(frames.replace(FIXTURE_NONCE, fields.get("nonce")), exit);
        });
    }

    /** {@link ProcessQueryPort#queueEntries} answering a recorded {@code queueLog} answer. */
    private final class RecordedRows implements ProcessQueryPort {

        private final byte[] answer;

        RecordedRows(byte[] answer) {
            this.answer = answer;
        }

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
            rowReads.incrementAndGet();
            return QueueEntryRowFixtures.queueEntries(NODE, answer);
        }
    }

    /** One ERROR row on the first read; on later reads only if {@code rowStays}. */
    private ProcessQueryPort rows(boolean rowStays) {
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
                if (rowReads.incrementAndGet() > 1 && !rowStays) {
                    return new QueueEntries(List.of(), 0);
                }
                return new QueueEntries(List.of(new QueueEntryRow(processId, Optional.empty(),
                    ProcessState.ERROR, "Error", Instant.parse("2026-11-10T09:00:00Z"),
                    "Workflow-0001", Optional.of("Module-0001"), Optional.empty(), FILE, 772,
                    new TreeMap<>())), 1);
            }
        };
    }

    // --- assertions ---------------------------------------------------------------------------

    /** One {@code REST_90} request for {@code file} to the base address, without secrets. */
    private void assertRestRequest(FakeProcessLauncher launcher, String file) {
        assertThat(launcher.launchCount()).isOne();
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request).containsEntry("variant", "REST_90")
                .containsEntry("url", "http://n90.example.test:8000")
                .containsEntry("objectType", "queue_manager@@@Workflow-0001@@@error")
                .containsEntry("fileName", file)
                .containsEntry("user", QueueEntryVersionProfile.USER);
            assertThat(request.get("nonce")).matches("[0-9a-f]{32}");
        });
        QueueEntryVersionProfile.assertLaunchedWithoutSecrets(launcher.last().spec());
        profile.assertTempRootEmpty();
    }

    private static void assertNoLeak(String text) {
        TestSecrets.assertNoLeak(text);
        assertThat(text).doesNotContain(INUBIT_TEXTS);
    }

    private ToolError errorOf(Runnable call) {
        ToolErrorException thrown = catchThrowableOfType(ToolErrorException.class, call::run);
        assertThat(thrown).as("the call fails").isNotNull();
        assertNoLeak(thrown.error().toString());
        return thrown.error();
    }

    private String lastLogLine() {
        return appender.list.stream()
            .filter(event -> event.getLevel() == Level.INFO)
            .map(ILoggingEvent::getFormattedMessage)
            .filter(message -> message.startsWith("read_queue_entry on "))
            .reduce((first, second) -> second)
            .orElseThrow(() -> new AssertionError("no read_queue_entry log line"));
    }

    private static byte[] fixture(String name) {
        return optionalFixture(name).orElseThrow(
            () -> new IllegalArgumentException("No fixture " + RESOURCES + name));
    }

    private static Optional<byte[]> optionalFixture(String name) {
        try (InputStream in = QueueEntryV90ContractTest.class.getResourceAsStream(
            RESOURCES + name)) {
            return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(String file, byte[] content) {
        try {
            Files.write(Path.of(file), content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
