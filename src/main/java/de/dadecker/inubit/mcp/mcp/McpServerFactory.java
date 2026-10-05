package de.dadecker.inubit.mcp.mcp;

import de.dadecker.inubit.mcp.domain.model.ProfileInfo;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Builds and starts the MCP stdio server (research R-1, R-16) on SDK 2.0.1: server info
 * {@value #SERVER_NAME} plus version and the profile title, the profile instructions, the tools
 * capability, and one tool per {@link ToolHandler}.
 *
 * <p>The profile is visible to the model in three places (002 FR-003, research D-4/D-5): the
 * {@code serverInfo.title} "INUBIT MCP – &lt;profile&gt;", the {@code instructions}, and the
 * prefix {@code [<profile>: <description>] } of every tool description. Tool descriptions, titles
 * and the {@code description} strings of the schemas are templates; they are rendered here with
 * the profile's terminology, so an unknown placeholder fails the start.
 *
 * <p>The SDK validates every call's arguments against the input schema (rejected calls never
 * reach the handler) and every successful structured result against the output schema.
 * {@link ResultMapper} builds the results.
 *
 * <p>The protocol output is passed in explicitly: in production it is the real stdout captured by
 * {@code StdoutGuard} before {@code System.out} is redirected to stderr.
 */
public final class McpServerFactory {

    public static final String SERVER_NAME = "inubit-mcp-server";

    /** {@code serverInfo.title} before the profile name. */
    static final String TITLE_PREFIX = "INUBIT MCP – ";

    private final String version;
    private final ProfileInfo profile;
    private final List<ToolHandler> handlers;
    private final SchemaResources schemas;
    private final ResultMapper resultMapper;
    private final SchemaRenderer schemaRenderer;

    /**
     * @param profile the profile whose name, description and terminology the server info, the
     *     instructions and the rendered tool descriptions and schema descriptions carry
     */
    public McpServerFactory(String version, ProfileInfo profile, List<ToolHandler> handlers,
        SchemaResources schemas, ResultMapper resultMapper) {
        this.version = Objects.requireNonNull(version, "version");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.handlers = List.copyOf(handlers);
        this.schemas = Objects.requireNonNull(schemas, "schemas");
        this.resultMapper = Objects.requireNonNull(resultMapper, "resultMapper");
        this.schemaRenderer = new SchemaRenderer(profile);
        Set<String> names = new HashSet<>();
        for (ToolHandler handler : this.handlers) {
            if (!names.add(handler.name())) {
                throw new IllegalArgumentException("Duplicate tool name " + handler.name());
            }
        }
    }

    /**
     * Starts serving on {@code in}/{@code out}; returns immediately.
     *
     * @throws IllegalStateException if a tool schema is missing or malformed
     */
    public RunningServer start(InputStream in, OutputStream out) {
        McpJsonMapper mapper = McpJsonDefaults.getMapper();
        List<SyncToolSpecification> tools = handlers.stream()
            .map(handler -> specification(mapper, handler))
            .toList();
        CountDownLatch inputClosed = new CountDownLatch(1);
        // serialized outbound emission: see SerializingTransportProvider (Phase 3 review M1)
        McpServerTransportProvider transport = new SerializingTransportProvider(
            new StdioServerTransportProvider(mapper,
                // 002 US2 review: only lines the SDK can process, so that it reads to the end
                new InboundLineFilter(new EofSignallingInputStream(in, inputClosed)), out));
        McpSyncServer server = McpServer.sync(transport)
            .serverInfo(Implementation.builder(SERVER_NAME, version).title(title()).build())
            .instructions(instructions())
            .capabilities(ServerCapabilities.builder().tools(false).build())
            .validateToolInputs(true)
            .tools(tools)
            .build();
        return new RunningServer(server, inputClosed);
    }

    /** {@code serverInfo.title}: "INUBIT MCP – <profile>" (research D-5, spike T-S1). */
    String title() {
        return TITLE_PREFIX + profile.name();
    }

    /**
     * The server {@code instructions} of the {@code initialize} result: the profile, its
     * description and the terminology (research D-5).
     */
    String instructions() {
        // the description is inserted after rendering: it is text from the file, not a template
        return "INUBIT systems of profile " + profile.name()
            + profile.description().map(description -> " (" + description + ")").orElse("")
            + profile.render(". The targets are {groups} and their {nodes}. {Node} ids have the"
                + " form `<{group}>/<{node}>`; the id of one {group} selects all its {nodes}. Read"
                + " tools take the id of one {group} or one {node} in `target`; write tools (if"
                + " offered) take exactly one {node} id in `node`. Results use the fields `group`"
                + " and `node` for these levels. Call list_nodes first to learn the valid ids.");
    }

    /**
     * The tool description the model reads: the profile prefix ({@code [<profile>: <description>] }
     * or {@code [<profile>] }) and the rendered template (research D-4).
     */
    String description(ToolHandler handler) {
        return profile.description()
            .map(description -> "[" + profile.name() + ": " + description + "] ")
            .orElse("[" + profile.name() + "] ")
            + profile.render(handler.descriptionText());
    }

    private SyncToolSpecification specification(McpJsonMapper mapper, ToolHandler handler) {
        ToolHints hints = handler.annotations();
        String title = profile.render(hints.title());
        Tool tool = Tool.builder(handler.name(), mapper,
                schemaRenderer.render(schemas.load(handler.inputSchemaResource())))
            .title(title)
            .description(description(handler))
            .outputSchema(mapper,
                schemaRenderer.render(schemas.load(handler.outputSchemaResource())))
            .annotations(ToolAnnotations.builder()
                .title(title)
                .readOnlyHint(hints.readOnly())
                .destructiveHint(hints.destructive())
                .idempotentHint(hints.idempotent())
                .openWorldHint(hints.openWorld())
                .build())
            .build();
        return SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> resultMapper.invoke(handler, request.arguments(),
                context(exchange)))
            .build();
    }

    /** The client's {@code clientInfo} from {@code initialize}, if it sent one. */
    private static CallContext context(McpSyncServerExchange exchange) {
        Implementation client = exchange == null ? null : exchange.getClientInfo();
        return client == null ? CallContext.NONE
            : CallContext.of(client.name(), client.version());
    }

    /** A started server; it serves until its input reaches end of stream. */
    public static final class RunningServer implements AutoCloseable {

        private final McpSyncServer server;
        private final CountDownLatch inputClosed;

        private RunningServer(McpSyncServer server, CountDownLatch inputClosed) {
            this.server = server;
            this.inputClosed = inputClosed;
        }

        /** Blocks until the client closes the input (end of stream). */
        public void awaitInputClosed() throws InterruptedException {
            inputClosed.await();
        }

        /** As {@link #awaitInputClosed()}, with a timeout; true if the input was closed. */
        public boolean awaitInputClosed(Duration timeout) {
            try {
                return inputClosed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        /** Stops the server gracefully. */
        @Override
        public void close() {
            server.closeGracefully();
        }
    }

    /** Signals end of stream (or a read failure) so that the server can shut down. */
    private static final class EofSignallingInputStream extends InputStream {

        private final InputStream delegate;
        private final CountDownLatch eof;

        EofSignallingInputStream(InputStream delegate, CountDownLatch eof) {
            this.delegate = Objects.requireNonNull(delegate, "in");
            this.eof = eof;
        }

        @Override
        public int read() throws IOException {
            return signalOnEof(guarded(() -> delegate.read()));
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return signalOnEof(guarded(() -> delegate.read(buffer, offset, length)));
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public void close() throws IOException {
            eof.countDown();
            delegate.close();
        }

        private int guarded(IoRead read) throws IOException {
            try {
                return read.read();
            } catch (IOException e) {
                eof.countDown();
                throw e;
            }
        }

        private int signalOnEof(int result) {
            if (result < 0) {
                eof.countDown();
            }
            return result;
        }

        @FunctionalInterface
        private interface IoRead {
            int read() throws IOException;
        }
    }
}
