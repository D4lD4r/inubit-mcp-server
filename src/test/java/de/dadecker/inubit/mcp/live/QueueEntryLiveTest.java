package de.dadecker.inubit.mcp.live;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.TestWiring;
import de.dadecker.inubit.mcp.adapter.cli.SystemProcessLauncher;
import de.dadecker.inubit.mcp.adapter.os.OsPrivateFileSystem;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.EntryPart;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.QueueEntries;
import de.dadecker.inubit.mcp.domain.model.QueueEntryRef;
import de.dadecker.inubit.mcp.domain.model.QueueEntryRow;
import de.dadecker.inubit.mcp.domain.model.RawEntryContent;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.live.QueueEntryLiveTestSupport.Entry;
import de.dadecker.inubit.mcp.live.QueueEntryLiveTestSupport.HelperRecorder;
import de.dadecker.inubit.mcp.live.QueueEntryLiveTestSupport.HelperRun;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;

/**
 * Feature 020 T033 (research D-21, SC-004): {@code read_queue_entry} against a real,
 * <b>non-production</b> INUBIT server (opt-in, Constitution III) through the production wiring.
 * Read-only: the queue rows of the process are read before and after the entry is read, and the
 * entry must keep its state, {@code startTime}, {@code fileSize} and {@code fileName}; the result
 * has data and variables; neither the captured log nor the result contains an original value of
 * a masked variable (from the raw {@code .wfsp} the helper wrote) or a secret known to the
 * scrubber, and the log contains none of the returned values. Run with
 * {@code INUBIT_MCP_PROFILE=<profile> INUBIT_LIVE_NODE=test/node1
 * INUBIT_LIVE_QUEUE_ENTRY=<processId>[/<fileName>] mvn verify -Plive
 * -Dtest=QueueEntryLiveTest}; the node's group needs {@code unsupported.queueEntryRead: true};
 * configuration, credentials and the production refusal: {@link LiveTarget}.
 *
 * <p>With {@code INUBIT_LIVE_RECORD_DIR} (a private directory outside the repository) the helper's
 * raw output — frames, exit code, {@code .data}, {@code .wfsp} — is written there owner-only as
 * the case {@code error_entry}, and one negative case runs directly on the adapter: the entry's
 * workflow with the fictitious file name {@value QueueEntryLiveTestSupport#NOT_FOUND_FILE_NAME}
 * (the login succeeds, so no {@code AUTH_FAILED} back-off), recorded as {@code not_found_file}.
 * A read that started no helper fails, so nothing is reported as recorded without
 * {@code error_entry}.
 * Only sizes, counts and the process id are printed, never content.
 *
 * <p>With {@code INUBIT_LIVE_QUEUE_ENTRY_OPEN_9_0=true} (tasks.md T037) the wiring uses the test
 * declaration that supports reading queue entries on 9.0 instead of the production one, which
 * refuses it until the 9.0 recording is reviewed: {@link QueueEntryLiveTestSupport#matrix}.
 */
@Tag("live")
@Timeout(600)
class QueueEntryLiveTest {

    private static final Duration READ_WAIT = Duration.ofSeconds(300);

    @Test
    void anErrorEntryIsReadAndStaysUnchanged() throws IOException {
        LiveTarget live = LiveTarget.resolve();
        Entry entry = QueueEntryLiveTestSupport.entry(System.getenv());
        Optional<Path> recordDirectory = QueueEntryLiveTestSupport.recordDirectory(
            System.getenv(), Path.of("").toAbsolutePath());
        NodeId node = live.node();
        assertThat(live.loaded().config().effectiveNodes().stream()
            .filter(candidate -> candidate.id().equals(node))
            .anyMatch(EffectiveNodeConfig::queueEntryRead))
            .as(node + ": its group must set unsupported.queueEntryRead: true"
                + " (docs/live-tests.md)").isTrue();
        PrivateFileSystem files = OsPrivateFileSystem.forHost();
        Optional<Path> run = recordDirectory.isPresent()
            ? Optional.of(QueueEntryLiveTestSupport.runDirectory(files, recordDirectory.get()))
            : Optional.empty();
        HelperRecorder recorder = new HelperRecorder(new SystemProcessLauncher());

        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger("de.dadecker.inubit");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logger.setAdditive(false);
        appender.start();
        logger.addAppender(appender);
        try (TestWiring wiring = live.wiring(recorder,
            QueueEntryLiveTestSupport.matrix(System.getenv()));
            McpTestClient client = McpTestClient.start(wiring.toolHandlers(),
                live.scrubber())) {
            client.initialize();
            ProcessQueryPort processes = wiring.gateways().processes(node);
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("target", node.value());
            arguments.put("processId", entry.processId());
            entry.fileName().ifPresent(fileName -> arguments.put("fileName", fileName));

            QueueEntries before = processes.queueEntries(entry.processId());
            JsonNode response = client.callTool("read_queue_entry", arguments, READ_WAIT);
            QueueEntries after = processes.queueEntries(entry.processId());

            String negative = "";
            List<HelperRun> readRuns = recorder.runs();
            if (run.isPresent() && !readRuns.isEmpty()) {
                QueueEntryLiveTestSupport.record(files, run.get(), "error_entry",
                    readRuns.getLast());
            }
            JsonNode result = structured(response);
            HelperRun readRun = QueueEntryLiveTestSupport.readRun(readRuns);
            QueueEntryRow read = QueueEntryLiveTestSupport.row(before,
                result.path("entry").path("fileName").asString()).orElseThrow(
                    () -> new AssertionError("the entry read is not among the queue rows of"
                        + " process " + entry.processId() + " read before it"));
            if (run.isPresent()) {
                int helperRuns = recorder.runs().size();
                String outcome = notFoundCase(wiring, node, read.workflow());
                assertThat(recorder.runs()).as("helper runs of the not-found case")
                    .hasSize(helperRuns + 1);
                QueueEntryLiveTestSupport.record(files, run.get(), "not_found_file",
                    recorder.runs().getLast());
                negative = "; not-found case " + outcome + "; recorded";
            }

            QueueEntryLiveTestSupport.assertUnchanged(read, after);
            JsonNode data = result.path("data");
            JsonNode variables = result.path("variables");
            assertThat(data.path("available").asBoolean())
                .as(() -> "data: " + data.path("reason").asString()).isTrue();
            assertThat(variables.path("available").asBoolean())
                .as(() -> "variables: " + variables.path("reason").asString()).isTrue();
            List<String> log = log(appender);
            QueueEntryLiveTestSupport.assertNotLogged(
                QueueEntryLiveTestSupport.returnedValues(result), log);
            QueueEntryLiveTestSupport.assertMaskedValuesAbsent(result, readRun.variables(), log);
            QueueEntryLiveTestSupport.assertNoKnownSecret(live.scrubber(), result, log);

            System.err.println("[live] read_queue_entry " + node + " process "
                + entry.processId() + ": data " + data.path("sizeBytes").asLong() + " bytes ("
                + data.path("kind").asString() + "), " + variables.path("totalCount").asInt()
                + " variables, entry unchanged" + negative);
        } finally {
            logger.detachAppender(appender);
            logger.setAdditive(true);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    /**
     * The negative case on the adapter: {@code workflow} with the fictitious file name; the
     * outcome per part or the error code, never content.
     */
    private static String notFoundCase(TestWiring wiring, NodeId node, String workflow) {
        try {
            RawEntryContent content = wiring.gateways().queueEntryContent(node).read(
                new QueueEntryRef(node, workflow, QueueEntryLiveTestSupport.NOT_FOUND_FILE_NAME,
                    0), wiring.gateways().detect(node), EnumSet.allOf(EntryPart.class));
            return "data " + content.data().getClass().getSimpleName() + ", variables "
                + content.variables().getClass().getSimpleName();
        } catch (ToolErrorException e) {
            return "error " + e.error().code();
        }
    }

    /** The captured log lines, with the stack traces of logged exceptions. */
    private static List<String> log(ListAppender<ILoggingEvent> appender) {
        List<String> lines = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            lines.add(event.getFormattedMessage());
            if (event.getThrowableProxy() != null) {
                lines.add(ThrowableProxyUtil.asString(event.getThrowableProxy()));
            }
        }
        return lines;
    }

    /** The structured result; a failure shows only the error. */
    private static JsonNode structured(JsonNode result) {
        assertThat(result.path("isError").asBoolean())
            .as(() -> result.path("content").toString()).isFalse();
        return result.path("structuredContent");
    }
}
