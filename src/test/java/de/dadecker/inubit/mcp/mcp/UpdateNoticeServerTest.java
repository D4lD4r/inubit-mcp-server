package de.dadecker.inubit.mcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.UpdateCache;
import de.dadecker.inubit.mcp.domain.model.SemVer;
import de.dadecker.inubit.mcp.domain.model.ServerUpdate;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feature 016 (FR-017 to FR-019) through the real SDK on stdio pipes: the instructions sentence,
 * the note block of the first result, and the dialog the server sends from its own thread after
 * the tool result went out.
 */
@Timeout(30)
class UpdateNoticeServerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<SemVer> dismissed = new CopyOnWriteArrayList<>();
    private final ExecutorService dialogs = UpdateNotice.dialogExecutor();
    private PipedOutputStream toServer;
    private Lines fromServer;
    private McpServerFactory.RunningServer server;

    @AfterEach
    void tearDown() throws IOException {
        if (toServer != null) {
            toServer.close();
        }
        if (server != null) {
            server.close();
        }
        dialogs.shutdownNow();
    }

    @Test
    void aClientWithElicitationGetsTheNoteAndTheDialogAndCanDismissTheVersion() throws Exception {
        start();
        JsonNode initialize = request(1, "initialize", "{\"protocolVersion\":\"2025-11-25\","
            + "\"capabilities\":{\"elicitation\":{}},\"clientInfo\":{\"name\":\"t\","
            + "\"version\":\"1\"}}");
        assertThat(initialize.path("result").path("instructions").asString()).endsWith(
            " A newer inubit-mcp-server 0.8.0 is available (installed 0.7.1):"
            + " https://github.com/D4lD4r/inubit-mcp-server/releases/tag/v0.8.0; update manually.");
        send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

        send(call(2));
        JsonNode first = fromServer.next();
        JsonNode dialog = fromServer.next();
        assertThat(first.path("id").asInt()).as("the result does not wait for the dialog")
            .isEqualTo(2);
        JsonNode content = first.path("result").path("content");
        assertThat(content).hasSize(2);
        assertThat(content.get(1).path("text").asString()).startsWith(
            "Note: inubit-mcp-server 0.8.0 is available (installed 0.7.1)");
        assertThat(dialog.path("method").asString()).isEqualTo("elicitation/create");
        assertThat(dialog.path("params").path("message").asString())
            .contains("0.8.0", "0.7.1", "manual");
        assertThat(dialog.path("params").path("requestedSchema").path("properties")
            .path("doNotRemindAgain").path("type").asString()).isEqualTo("boolean");

        send("{\"jsonrpc\":\"2.0\",\"id\":" + JSON.writeValueAsString(dialog.path("id"))
            + ",\"result\":{\"action\":\"accept\",\"content\":{\"doNotRemindAgain\":true}}}");
        send(call(3));
        JsonNode second = fromServer.next();
        assertThat(second.path("id").asInt()).isEqualTo(3);
        assertThat(second.path("result").path("content")).hasSize(1);
        for (int i = 0; i < 50 && dismissed.isEmpty(); i++) {
            Thread.sleep(100);
        }
        assertThat(dismissed).containsExactly(SemVer.parse("0.8.0").orElseThrow());
        assertThat(fromServer.pending()).as("one dialog only").isZero();
    }

    @Test
    void aClientWithoutElicitationGetsOnlyTheNote() throws Exception {
        start();
        request(1, "initialize", "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}");
        send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

        send(call(2));
        assertThat(fromServer.next().path("result").path("content")).hasSize(2);
        send(call(3));
        assertThat(fromServer.next().path("result").path("content")).hasSize(1);
        Thread.sleep(300);
        assertThat(fromServer.pending()).as("no dialog").isZero();
    }

    private void start() throws IOException {
        UpdateCache cache = new UpdateCache() {
            @Override
            public Optional<CachedRelease> read() {
                return Optional.empty();
            }

            @Override
            public void write(SemVer latestVersion, Instant checkedAt) {
            }

            @Override
            public void dismiss(SemVer version) {
                dismissed.add(version);
            }
        };
        Optional<ServerUpdate> update = Optional.of(ServerUpdate.of(
            SemVer.parse("0.7.1").orElseThrow(), SemVer.parse("0.8.0").orElseThrow(),
            Instant.parse("2026-11-06T12:00:00Z")));
        McpServerFactory factory = new McpServerFactory("0.7.1", McpTestClient.TEST_PROFILE,
            List.of(new EchoTestTool()), SchemaResources.forClasspath(),
            new ResultMapper(new SecretScrubber()), new UpdateNotice(() -> update, cache, dialogs));
        PipedInputStream serverIn = new PipedInputStream(1 << 16);
        toServer = new PipedOutputStream(serverIn);
        fromServer = new Lines();
        server = factory.start(serverIn, fromServer);
    }

    private static String call(int id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":"
            + "{\"name\":\"echo_test\",\"arguments\":{\"message\":\"hi\"}}}";
    }

    private JsonNode request(int id, String method, String params) throws Exception {
        send("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":"
            + params + "}");
        return fromServer.next();
    }

    private void send(String line) throws IOException {
        toServer.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        toServer.flush();
    }

    private static final class Lines extends OutputStream {

        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final ByteArrayOutputStream current = new ByteArrayOutputStream();

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                lines.add(current.toString(StandardCharsets.UTF_8));
                current.reset();
            } else {
                current.write(b);
            }
        }

        JsonNode next() throws InterruptedException {
            String line = lines.poll(10, TimeUnit.SECONDS);
            assertThat(line).as("a line within 10 s").isNotNull();
            return JSON.readTree(line);
        }

        int pending() {
            return lines.size();
        }
    }
}
