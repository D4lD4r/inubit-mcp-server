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
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import de.dadecker.inubit.mcp.live.ServerLogLiveTestSupport.ProcessRecorder;
import de.dadecker.inubit.mcp.live.ServerLogLiveTestSupport.Run;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;

/**
 * Feature 021 T054 (research D-24, SC-001, SC-005): {@code download_server_log} against a real,
 * <b>non-production</b> INUBIT server (opt-in, Constitution III) through the production wiring.
 * Read-only: the trace log and, if the node's group sets {@code unsupported.serverLogRead: true},
 * the web service log are downloaded into {@code INUBIT_LIVE_LOG_FOLDER} (a private folder outside
 * every repository); each result matches the output schema and its file exists directly in the
 * folder with the reported size, owner-only ({@code rw-------}). Then one trace download through a
 * copy of the configuration whose node points at a closed loopback port must fail and leave no
 * file in the folder. The server log captured during the test (logger {@code de.dadecker.inubit}
 * at {@code DEBUG}) must contain no excerpt line. Run with {@code INUBIT_MCP_PROFILE=<profile>
 * INUBIT_LIVE_NODE=test/node1 INUBIT_LIVE_LOG_FOLDER=<folder> mvn verify -Plive
 * -Dtest=ServerLogLiveTest}; skipped without the node or the folder; configuration, credentials
 * and the production refusal: {@link LiveTarget}.
 *
 * <p>With {@code INUBIT_LIVE_RECORD_DIR} (a private directory outside every repository) the raw
 * output of the StartCLI trace download and of the helper's web service download is written
 * there owner-only as the case files of research D-21, before the result is checked. Prints one
 * {@code [live] download_server_log <node> <log>: <n> bytes, window <lines>} line per log, never
 * log content.
 */
@Tag("live")
@Timeout(1800)
class ServerLogLiveTest {

    private static final Duration CALL_WAIT = Duration.ofSeconds(600);

    @Test
    void theServerLogsAreDownloadedOwnerOnlyAndAFailedDownloadLeavesNoFile() throws IOException {
        LiveTarget live = LiveTarget.resolve();
        Path repository = Path.of("").toAbsolutePath();
        Path folder = ServerLogLiveTestSupport.logFolder(System.getenv(), repository);
        Optional<Path> recordDirectory = ServerLogLiveTestSupport.recordDirectory(
            System.getenv(), repository);
        NodeId node = live.node();
        boolean webService = live.loaded().config().effectiveNodes().stream()
            .filter(candidate -> candidate.id().equals(node))
            .anyMatch(EffectiveNodeConfig::serverLogRead);
        List<String> logs = webService ? List.of("trace", "webservice") : List.of("trace");
        PrivateFileSystem files = OsPrivateFileSystem.forHost();
        Optional<Path> run = recordDirectory.isPresent()
            ? Optional.of(ServerLogLiveTestSupport.runDirectory(files, recordDirectory.get()))
            : Optional.empty();
        ProcessRecorder recorder = new ProcessRecorder(new SystemProcessLauncher());

        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger("de.dadecker.inubit");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logger.setAdditive(false);
        appender.start();
        logger.addAppender(appender);
        try {
            List<JsonNode> results = new ArrayList<>();
            try (TestWiring wiring = live.wiring(recorder);
                McpTestClient client = McpTestClient.start(wiring.toolHandlers(),
                    live.scrubber())) {
                client.initialize();
                for (String log : logs) {
                    int before = recorder.runs().size();
                    JsonNode response = client.callTool("download_server_log",
                        arguments(node, log, folder), CALL_WAIT);
                    if (run.isPresent()) {
                        List<Run> runs = recorder.runs();
                        ServerLogLiveTestSupport.record(files, run.get(), log,
                            ServerLogLiveTestSupport.caseName(response),
                            ServerLogLiveTestSupport.lastRun(runs.subList(before, runs.size()),
                                log));
                    }
                    JsonNode result = structured(response);
                    ServerLogLiveTestSupport.assertDownloaded(result, node, log, folder, files);
                    results.add(result);
                }
            }

            List<String> before = listing(folder);
            try (TestWiring wiring = live.wiring(ServerLogLiveTestSupport.unreachable(
                    live.loaded().config(), node, closedLoopbackPort()));
                McpTestClient client = McpTestClient.start(wiring.toolHandlers(),
                    live.scrubber())) {
                client.initialize();
                JsonNode response = client.callTool("download_server_log",
                    arguments(node, "trace", folder), CALL_WAIT);
                assertThat(response.path("isError").asBoolean())
                    .as("the download through the unreachable port fails").isTrue();
            }
            assertThat(listing(folder))
                .as("no file of the failed download is left in the log folder").isEqualTo(before);

            List<String> captured = log(appender);
            for (JsonNode result : results) {
                ServerLogLiveTestSupport.assertNoExcerptLineLogged(result, captured);
                System.err.println(ServerLogLiveTestSupport.summary(result));
            }
        } finally {
            logger.detachAppender(appender);
            logger.setAdditive(true);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    private static Map<String, Object> arguments(NodeId node, String log, Path folder) {
        return Map.of("target", node.value(), "log", log, "targetFolder", folder.toString());
    }

    /** A loopback port nothing listens on: bound once, then released. */
    private static int closedLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    /** The names in {@code folder}, sorted. */
    private static List<String> listing(Path folder) throws IOException {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.map(entry -> entry.getFileName().toString()).sorted().toList();
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
