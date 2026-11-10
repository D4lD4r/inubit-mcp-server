package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import de.dadecker.inubit.mcp.domain.model.RawEntryContent;
import de.dadecker.inubit.mcp.domain.model.RawEntryContent.PartResult;
import de.dadecker.inubit.mcp.domain.model.RawVariable;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.infra.ResultJson;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Feature 020 (T024, FR-018, D-16): a {@code read_queue_entry} call logs exactly one INFO line
 * naming only the node, process ID, file name, sizes and the outcome; neither the data stream nor
 * a variable value nor a credential appears in any log line at any level.
 */
class QueueEntryLoggingTest {

    private static final NodeId TEST = NodeId.parse("test/node1");
    private static final String PID = "1234";
    private static final String FILE = "Module-0001(4711)@1234@0a1b2c3d";
    private static final String DATA = "<Order><Id>Order-0001</Id><wsse:Password>"
        + TestSecrets.CREDENTIAL_PAYLOAD_VALUE + "</wsse:Password><Login>"
        + TestSecrets.CONFIGURED_PASSWORD + "</Login><Note>Payload-Text-0001</Note></Order>";
    private static final List<RawVariable> VARIABLES = List.of(
        new RawVariable("password", Optional.empty(), TestSecrets.CREDENTIAL_PAYLOAD_VALUE),
        new RawVariable("var.customer", Optional.of("String"), "Customer-Value-0001"));

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-10T09:00:00Z"));
    private final NodeVersions versions = new NodeVersions(clock,
        node -> VersionSetting.AUTO, node -> Duration.ofMinutes(10));
    private final FakeVersionDetection detection =
        new FakeVersionDetection(versions).answer(TEST, "8.1.17");
    private final SecretScrubber scrubber = new SecretScrubber();
    private final Logger logger = (Logger) LoggerFactory.getLogger("de.dadecker.inubit");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;
    private List<QueueEntries> rows = List.of();
    private RawEntryContent content;
    private RuntimeException contentFailure;

    @BeforeEach
    void setUp() {
        scrubber.register(TestSecrets.CONFIGURED_PASSWORD);
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

    @Test
    void aFullCallLogsOneInfoLineWithoutAnyContent() {
        rows = List.of(entries(row()));
        content = new RawEntryContent(
            new PartResult.Read<>(DATA.getBytes(StandardCharsets.UTF_8)),
            new PartResult.Read<>(VARIABLES));

        QueueEntryResult result = service().read(TEST.value(), PID, Optional.empty(),
            OptionalInt.empty());

        assertThat(infoLines()).containsExactly("read_queue_entry on test/node1: process 1234,"
            + " file " + FILE + ", data " + DATA.getBytes(StandardCharsets.UTF_8).length
            + " bytes, variables 2, outcome OK");
        String logged = allLines();
        assertThat(logged).doesNotContain("Order-0001", "Payload-Text-0001",
            "Customer-Value-0001", "${secret:");
        TestSecrets.assertNoLeak(logged);
        // what was returned (except the metadata the line names) never reaches the log
        assertThat(ResultJson.mapper().writeValueAsString(result.data()))
            .contains("Payload-Text-0001");
    }

    @Test
    void aPartialReadLogsPartialWithoutTheMissingSize() {
        rows = List.of(entries(row()));
        content = new RawEntryContent(
            new PartResult.Read<>(DATA.getBytes(StandardCharsets.UTF_8)),
            new PartResult.Failed<>("INUBIT reported an error: "
                + TestSecrets.CONFIGURED_PASSWORD));

        service().read(TEST.value(), PID, Optional.of(FILE), OptionalInt.of(10));

        assertThat(infoLines()).containsExactly("read_queue_entry on test/node1: process 1234,"
            + " file " + FILE + ", data " + DATA.getBytes(StandardCharsets.UTF_8).length
            + " bytes, variables -, outcome PARTIAL");
        TestSecrets.assertNoLeak(allLines());
    }

    @Test
    void aFailedCallLogsItsErrorCode() {
        rows = List.of(new QueueEntries(List.of(), 0));

        catchThrowableOfType(ToolErrorException.class, () -> service().read(TEST.value(), PID,
            Optional.empty(), OptionalInt.empty()));

        assertThat(infoLines()).containsExactly("read_queue_entry on test/node1: process 1234,"
            + " file -, data - bytes, variables -, outcome NOT_FOUND");
    }

    @Test
    void aFailureAfterTheRowWasSelectedNamesItsFile() {
        rows = List.of(entries(row()));
        contentFailure = new ToolErrorException(ToolError.of(ErrorCode.CLI_UNAVAILABLE,
            "The helper failed: " + TestSecrets.CONFIGURED_PASSWORD + " "
                + TestSecrets.CREDENTIAL_PAYLOAD_VALUE, "cause", "next step"));

        catchThrowableOfType(ToolErrorException.class, () -> service().read(TEST.value(), PID,
            Optional.empty(), OptionalInt.empty()));
        contentFailure = new IllegalStateException("Payload-Text-0001 "
            + TestSecrets.CREDENTIAL_PAYLOAD_VALUE);
        catchThrowableOfType(IllegalStateException.class, () -> service().read(TEST.value(),
            PID, Optional.empty(), OptionalInt.empty()));

        assertThat(infoLines()).containsExactly(
            "read_queue_entry on test/node1: process 1234, file " + FILE + ", data - bytes,"
                + " variables -, outcome CLI_UNAVAILABLE",
            "read_queue_entry on test/node1: process 1234, file " + FILE + ", data - bytes,"
                + " variables -, outcome INTERNAL");
        String logged = allLines();
        assertThat(logged).doesNotContain("Payload-Text-0001", "helper failed");
        TestSecrets.assertNoLeak(logged);
    }

    @Test
    void invalidInputIsNotEchoedIntoTheLog() {
        catchThrowableOfType(ToolErrorException.class, () -> service().read(TEST.value(),
            TestSecrets.CONFIGURED_PASSWORD, Optional.empty(), OptionalInt.empty()));
        catchThrowableOfType(ToolErrorException.class, () -> service().read(TEST.value(), PID,
            Optional.of("a/" + TestSecrets.CREDENTIAL_PAYLOAD_VALUE), OptionalInt.empty()));
        catchThrowableOfType(ToolErrorException.class, () -> service().read(
            "test/" + TestSecrets.TRUST_STORE_PASSWORD, PID, Optional.empty(),
            OptionalInt.empty()));

        assertThat(infoLines()).containsExactly(
            "read_queue_entry on -: process -, file -, data - bytes, variables -,"
                + " outcome INVALID_INPUT",
            "read_queue_entry on -: process 1234, file -, data - bytes, variables -,"
                + " outcome INVALID_INPUT",
            "read_queue_entry on -: process 1234, file -, data - bytes, variables -,"
                + " outcome TARGET_UNKNOWN");
        TestSecrets.assertNoLeak(allLines());
    }

    private QueueEntryService service() {
        return new QueueEntryService(new TargetResolver(List.of(TEST)), node -> true,
            FakeVersionDetection.gateWithoutClients(versions, detection,
                CapabilityMatrix.production()),
            node -> processes(), node -> (ref, version, parts) -> {
                if (contentFailure != null) {
                    throw contentFailure;
                }
                return content;
            }, scrubber::scrub);
    }

    private ProcessQueryPort processes() {
        return new ProcessQueryPort() {
            private int answered;

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
                return rows.get(Math.min(answered++, rows.size() - 1));
            }
        };
    }

    private List<String> infoLines() {
        return appender.list.stream()
            .filter(event -> event.getLevel() == Level.INFO)
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    }

    private String allLines() {
        return String.join("\n", appender.list.stream()
            .map(event -> event.getFormattedMessage() + " " + event.getThrowableProxy())
            .toList());
    }

    private static QueueEntries entries(QueueEntryRow row) {
        return new QueueEntries(List.of(row), 1);
    }

    private static QueueEntryRow row() {
        return new QueueEntryRow(PID, Optional.empty(), ProcessState.ERROR, "Error",
            Instant.parse("2026-11-10T08:15:30Z"), "Workflow-0001", Optional.of("Module-0001"),
            Optional.of("Throw"), FILE, 772, new TreeMap<>(Map.of("userDefined1",
                "ACME-ERROR")));
    }
}
