package de.dadecker.inubit.mcp.adapter.cli.queue;

import static de.dadecker.inubit.mcp.adapter.cli.queue.QueueEntryVersionProfile.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher.Answer;
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
 * T032 (feature 020, FR-019, US3, research D-20): the INUBIT 9.0 contract of
 * {@code read_queue_entry} in the fallback state, while {@code fixtures/queue-entry/v9_0/} holds no
 * recorded {@code error_entry}. The shipped matrix refuses 9.0 "not yet verified" before any lookup
 * or launch. With 9.0 opened by the test-only {@link QueueEntryVersionProfile#OPENED}, the real
 * {@link QueueEntryService} and {@link CliQueueEntryAdapter} run the {@code REST_90} path over the
 * 9.0 cases reconstructed from the spike's texts: a fake launcher replays {@code <case>.frames}/
 * {@code <case>.exit} with the request's nonce; INUBIT's "not found" makes the service read the
 * row again (D-5): still there → partial result, gone → {@code NOT_FOUND}. No process, no network.
 */
@Timeout(60)
class QueueEntryV90ContractTest {

    /**
     * The helper cases this test runs; exactly the {@code v9_0/*.frames} on disk. The recorded
     * {@code error_entry} (tasks.md T037/T038) joins this list together with its own test.
     */
    static final List<String> CASES = List.of("missing_workflow", "not_found_file");

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
        "MissingWorkflowWithName", "IOException", "acme/workflow");

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

    /** FR-019 fallback; T038 replaces this test when the recorded error entry arrives. */
    @Test
    void whileTheErrorEntryIsAbsentNineZeroIsRefusedNotYetVerifiedWithoutLaunch() {
        assertThat(DIRECTORY.resolve("error_entry.origin")).as("FR-019 fallback state")
            .doesNotExist();
        FakeProcessLauncher launcher = replaying("not_found_file");

        ToolError error = errorOf(() -> service(CapabilityMatrix.production(), launcher, true)
            .read(NODE.value(), PID, Optional.empty(), OptionalInt.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.node()).contains(NODE);
        assertThat(error.message()).contains("not yet verified for INUBIT 9.0");
        assertThat(detection.requests()).containsExactly(NODE);
        assertThat(rowReads).as("queueLog lookups").hasValue(0);
        assertThat(launcher.launchCount()).isZero();
        profile.assertTempRootEmpty();
        assertThat(lastLogLine()).endsWith("outcome UNSUPPORTED_VERSION");
    }

    @ParameterizedTest
    // the not-found subset of CASES
    @ValueSource(strings = {"missing_workflow", "not_found_file"})
    void notFoundWhileTheRowStaysIsAPartialResultWithBothPartsUnavailable(String name) {
        FakeProcessLauncher launcher = replaying(name);

        QueueEntryResult result = service(QueueEntryVersionProfile.OPENED, launcher, true)
            .read(NODE.value(), PID, Optional.empty(), OptionalInt.empty());

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
        assertRestRequest(launcher);
        assertThat(lastLogLine()).endsWith("outcome PARTIAL");
    }

    @ParameterizedTest
    // the not-found subset of CASES
    @ValueSource(strings = {"missing_workflow", "not_found_file"})
    void notFoundAfterTheRowLeftIsNotFound(String name) {
        FakeProcessLauncher launcher = replaying(name);

        ToolError error = errorOf(() -> service(QueueEntryVersionProfile.OPENED, launcher, false)
            .read(NODE.value(), PID, Optional.empty(), OptionalInt.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(error.node()).contains(NODE);
        assertThat(error.message()).isEqualTo("Queue entry " + FILE + " of process " + PID
            + " left the Queue Manager of " + NODE + " (restarted, killed or deleted meanwhile)");
        assertThat(rowReads).as("the rows are read again after not found").hasValue(2);
        assertRestRequest(launcher);
        assertThat(lastLogLine()).endsWith("outcome NOT_FOUND");
    }

    // --- the run ------------------------------------------------------------------------------

    /**
     * {@code read_queue_entry} for {@link #NODE} (detected as 9.0.10) through the gate of
     * {@code matrix}; the re-read after "not found" finds the row again only if {@code rowStays}.
     */
    private QueueEntryService service(CapabilityMatrix matrix, FakeProcessLauncher launcher,
        boolean rowStays) {
        CliQueueEntryAdapter adapter = profile.adapter(
            profile.node(NODE.value(), VersionSetting.AUTO).build(), launcher);
        return new QueueEntryService(new TargetResolver(List.of(NODE)), node -> true,
            FakeVersionDetection.gateWithoutClients(versions, detection, matrix),
            node -> rows(rowStays), node -> adapter, scrubber::scrub);
    }

    /** A helper replaying the case's frames and exit code with the request's nonce. */
    private FakeProcessLauncher replaying(String name) {
        String frames = new String(fixture(name + FRAMES), StandardCharsets.UTF_8);
        int exit = Integer.parseInt(new String(fixture(name + ".exit"),
            StandardCharsets.UTF_8).strip());
        return FakeProcessLauncher.of("", "", 0).responding(stdin -> {
            Map<String, String> fields = request(stdin);
            requests.add(fields);
            return new Answer(frames.replace(FIXTURE_NONCE, fields.get("nonce")), exit);
        });
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

    /** One {@code REST_90} request to the base address, launched without secrets. */
    private void assertRestRequest(FakeProcessLauncher launcher) {
        assertThat(launcher.launchCount()).isOne();
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request).containsEntry("variant", "REST_90")
                .containsEntry("url", "http://n90.example.test:8000")
                .containsEntry("objectType", "queue_manager@@@Workflow-0001@@@error")
                .containsEntry("fileName", FILE)
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
        try (InputStream in = QueueEntryV90ContractTest.class.getResourceAsStream(
            RESOURCES + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + RESOURCES + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
