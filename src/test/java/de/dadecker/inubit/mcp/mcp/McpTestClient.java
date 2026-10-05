package de.dadecker.inubit.mcp.mcp;

import static org.assertj.core.api.Assertions.fail;

import de.dadecker.inubit.mcp.domain.model.ProfileInfo;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.infra.StdoutGuard;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test helper (T024): runs the real MCP server of {@link McpServerFactory} on piped streams and
 * drives it with raw JSON-RPC lines, the way an MCP client does over stdio (research R-17).
 *
 * <p>Everything the server writes to its protocol output is kept verbatim in
 * {@link #rawOutput()}, so that tests can assert that nothing but JSON-RPC lines reaches it.
 */
public final class McpTestClient implements AutoCloseable {

    public static final String PROTOCOL_VERSION = "2025-11-25";

    /**
     * The profile of {@link #start(List)}: {@code acme} without description and with the default
     * terminology, so tool descriptions start with {@code [acme] } and say "group"/"node".
     */
    public static final ProfileInfo TEST_PROFILE = new ProfileInfo("acme", Optional.empty(),
        Terminology.DEFAULT, "INUBIT_ACME");
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(10);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PipedOutputStream toServer;
    private final LineCapture fromServer;
    private final McpServerFactory.RunningServer server;
    private final AtomicLong ids = new AtomicLong();
    private final List<JsonNode> notifications = new ArrayList<>();
    private Runnable onClose = () -> { };

    private McpTestClient(PipedOutputStream toServer, LineCapture fromServer,
        McpServerFactory.RunningServer server) {
        this.toServer = toServer;
        this.fromServer = fromServer;
        this.server = server;
    }

    /**
     * Starts a server for {@link #TEST_PROFILE} with {@code handlers}, the test-classpath schemas
     * and a fresh scrubber.
     */
    public static McpTestClient start(List<ToolHandler> handlers) {
        return start(handlers, new SecretScrubber());
    }

    public static McpTestClient start(List<ToolHandler> handlers, SecretScrubber scrubber) {
        return start(TEST_PROFILE, handlers, scrubber);
    }

    /** As {@link #start(List)}, for {@code profile}. */
    public static McpTestClient start(ProfileInfo profile, List<ToolHandler> handlers) {
        return start(profile, handlers, new SecretScrubber());
    }

    private static McpTestClient start(ProfileInfo profile, List<ToolHandler> handlers,
        SecretScrubber scrubber) {
        return start(new McpServerFactory("0.0.0-test", profile, handlers,
            SchemaResources.forClasspath(), new ResultMapper(scrubber)));
    }

    public static McpTestClient start(McpServerFactory factory) {
        try {
            PipedInputStream serverIn = new PipedInputStream(1 << 16);
            PipedOutputStream toServer = new PipedOutputStream(serverIn);
            LineCapture fromServer = new LineCapture();
            return new McpTestClient(toServer, fromServer, factory.start(serverIn, fromServer));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Starts a server whose protocol output is the "real stdout" captured by
     * {@link StdoutGuard#install()}, as in production: while the client runs, {@code System.out}
     * goes to stderr, so stray writes can be checked against {@link #rawOutput()}.
     */
    public static McpTestClient startOnGuardedStdout(List<ToolHandler> handlers) {
        PrintStream originalOut = System.out;
        LineCapture capture = new LineCapture();
        System.setOut(new PrintStream(capture, true, StandardCharsets.UTF_8));
        PrintStream protocolOut = StdoutGuard.install();
        try {
            PipedInputStream serverIn = new PipedInputStream(1 << 16);
            PipedOutputStream toServer = new PipedOutputStream(serverIn);
            McpServerFactory factory = new McpServerFactory("0.0.0-test", TEST_PROFILE,
                handlers, SchemaResources.forClasspath(), new ResultMapper(new SecretScrubber()));
            McpTestClient client = new McpTestClient(toServer, capture,
                factory.start(serverIn, protocolOut));
            client.onClose = () -> {
                StdoutGuard.restore();
                System.setOut(originalOut);
            };
            return client;
        } catch (IOException | RuntimeException e) {
            StdoutGuard.restore();
            System.setOut(originalOut);
            throw e instanceof IOException io ? new UncheckedIOException(io)
                : (RuntimeException) e;
        }
    }

    /** {@code initialize} followed by {@code notifications/initialized}; returns the result. */
    public JsonNode initialize() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "mcp-test-client", "version", "1.0"));
        JsonNode result = request("initialize", params);
        notify("notifications/initialized");
        return result;
    }

    /** {@code tools/list}; returns the result ({@code {"tools": [...]}}). */
    public JsonNode listTools() {
        return request("tools/list", Map.of());
    }

    /** {@code tools/call}; returns the result (content, structuredContent, isError). */
    public JsonNode callTool(String name, Map<String, Object> arguments) {
        return request("tools/call", Map.of("name", name, "arguments", arguments));
    }

    /** As {@link #callTool(String, Map)}, waiting up to {@code timeout} (e.g. live CLI exports). */
    public JsonNode callTool(String name, Map<String, Object> arguments, Duration timeout) {
        return request("tools/call", Map.of("name", name, "arguments", arguments), timeout);
    }

    /** Sends a request and returns its {@code result}; fails on a JSON-RPC error or timeout. */
    public JsonNode request(String method, Object params) {
        return request(method, params, RESPONSE_TIMEOUT);
    }

    private JsonNode request(String method, Object params, Duration timeout) {
        JsonNode response = rawRequest(method, params, timeout);
        if (response.has("error")) {
            fail("JSON-RPC error for " + method + ": " + response.get("error"));
        }
        return response.get("result");
    }

    /** Sends a request and returns the whole response (result or error). */
    public JsonNode rawRequest(String method, Object params) {
        return rawRequest(method, params, RESPONSE_TIMEOUT);
    }

    private JsonNode rawRequest(String method, Object params, Duration timeout) {
        long id = ids.incrementAndGet();
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.put("params", params);
        send(message);
        return awaitResponse(id, timeout);
    }

    /**
     * Sends all {@code tools/call} requests at once (without waiting in between) and collects
     * every response line until each request id has been answered or the timeout expired.
     *
     * @return the responses per request id, in arrival order (a correct server has exactly one)
     */
    public Map<Long, List<JsonNode>> callToolsConcurrently(String name,
        List<Map<String, Object>> argumentsPerCall) {
        List<Long> sent = new ArrayList<>();
        for (Map<String, Object> arguments : argumentsPerCall) {
            long id = ids.incrementAndGet();
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("jsonrpc", "2.0");
            message.put("id", id);
            message.put("method", "tools/call");
            message.put("params", Map.of("name", name, "arguments", arguments));
            send(message);
            sent.add(id);
        }
        Map<Long, List<JsonNode>> responses = new LinkedHashMap<>();
        sent.forEach(id -> responses.put(id, new ArrayList<>()));
        long deadline = System.nanoTime() + RESPONSE_TIMEOUT.toNanos();
        while (responses.values().stream().anyMatch(List::isEmpty)) {
            long remaining = deadline - System.nanoTime();
            String line;
            try {
                line = remaining <= 0 ? null
                    : fromServer.lines.poll(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            if (line == null) {
                break;
            }
            JsonNode message = JSON.readTree(line);
            if (message.has("id")) {
                responses.computeIfAbsent(message.get("id").asLong(), id -> new ArrayList<>())
                    .add(message);
            } else {
                notifications.add(message);
            }
        }
        return responses;
    }

    public void notify(String method) {
        send(Map.of("jsonrpc", "2.0", "method", method));
    }

    /** Everything the server wrote to the protocol output so far, verbatim. */
    public String rawOutput() {
        return fromServer.raw();
    }

    public List<JsonNode> notifications() {
        return List.copyOf(notifications);
    }

    /** Closes the server's input (EOF, like a client exiting) and waits for the server. */
    @Override
    public void close() {
        try {
            toServer.close();
        } catch (IOException e) {
            // already closed
        }
        server.awaitInputClosed(Duration.ofSeconds(5));
        server.close();
        onClose.run();
    }

    private void send(Map<String, Object> message) {
        try {
            toServer.write((JSON.writeValueAsString(message) + "\n")
                .getBytes(StandardCharsets.UTF_8));
            toServer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private JsonNode awaitResponse(long id, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long remaining = deadline - System.nanoTime();
            String line;
            try {
                line = remaining <= 0 ? null
                    : fromServer.lines.poll(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            if (line == null) {
                return fail("No response with id " + id + " within " + timeout
                    + "; output so far: " + rawOutput());
            }
            JsonNode message = JSON.readTree(line);
            if (message.has("id") && message.get("id").asLong() == id) {
                return message;
            }
            if (!message.has("id")) {
                notifications.add(message);
            }
        }
    }

    /** Records the protocol output verbatim and splits it into lines. */
    private static final class LineCapture extends OutputStream {

        private final ByteArrayOutputStream all = new ByteArrayOutputStream();
        private final ByteArrayOutputStream current = new ByteArrayOutputStream();
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();

        @Override
        public synchronized void write(int b) {
            all.write(b);
            if (b == '\n') {
                lines.add(current.toString(StandardCharsets.UTF_8));
                current.reset();
            } else {
                current.write(b);
            }
        }

        synchronized String raw() {
            return all.toString(StandardCharsets.UTF_8);
        }
    }
}
