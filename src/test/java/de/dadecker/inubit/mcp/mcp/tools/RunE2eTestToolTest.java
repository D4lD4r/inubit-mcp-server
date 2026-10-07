package de.dadecker.inubit.mcp.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.DevelopmentGuard;
import de.dadecker.inubit.mcp.application.E2eTestService;
import de.dadecker.inubit.mcp.application.ImportService;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.application.WriteChallengeRegistry;
import de.dadecker.inubit.mcp.domain.model.DevelopmentPolicy;
import de.dadecker.inubit.mcp.domain.model.E2ePolicy;
import de.dadecker.inubit.mcp.domain.model.LogEntry;
import de.dadecker.inubit.mcp.domain.model.LogQuery;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.domain.model.ProcessRows;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.port.E2ePort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * T023 (feature 004, contracts/mcp-tools-delta.md): {@code run_e2e_test} over MCP —
 * destructive annotations, the contract description and input schema, the result as structured
 * content validated against the output schema.
 */
class RunE2eTestToolTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final String DESCRIPTION = "[acme] Send a SOAP envelope from the workspace to"
        + " an endpoint of ONE node and report the response and the process instances, errors"
        + " and log entries it caused. Allowed only where e2eTests permits.";

    @TempDir
    Path root;

    private final List<AutoCloseable> closeables = new ArrayList<>();
    private final List<E2ePort.Message> sent = new CopyOnWriteArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable closeable : closeables) {
            closeable.close();
        }
    }

    private McpTestClient client() throws IOException {
        Files.createDirectories(root.resolve("samples"));
        Files.writeString(root.resolve("samples/order.xml"), "<Envelope/>");
        DevelopmentPolicy dev = new DevelopmentPolicy(DEV, false, true,
            WritePolicy.Confirmation.SERVER, Duration.ofMinutes(5), E2ePolicy.FREE,
            Optional.of(URI.create("https://inubit-dev-1.example.test:8443")));
        E2ePort port = new E2ePort() {
            @Override
            public String endpoint(String path) {
                return "https://inubit-dev-1.example.test:8443" + path;
            }

            @Override
            public Exchange post(Message message) {
                sent.add(message);
                return new Exchange(endpoint(message.path()), Optional.of(200),
                    "<ok/>".getBytes(StandardCharsets.UTF_8), false, Duration.ofMillis(12),
                    false);
            }
        };
        LogPort logs = new LogPort() {
            @Override
            public void validate(LogQuery query) {
            }

            @Override
            public Page<LogEntry> query(LogQuery query) {
                return new Page<>(List.of(), 0, query.limit(), OptionalLong.of(0), false, false,
                    OptionalInt.empty());
            }
        };
        ProcessQueryPort processes = new ProcessQueryPort() {
            @Override
            public void validate(ProcessQuery query) {
            }

            @Override
            public Page<ProcessInstance> find(ProcessQuery query) {
                return new Page<>(List.of(), 0, query.limit(), OptionalLong.of(0), false, false,
                    OptionalInt.empty());
            }

            @Override
            public ProcessRows findByProcessId(String id, Instant now, Duration threshold) {
                return new ProcessRows(List.of(), 0);
            }
        };
        MutableClock clock = new MutableClock(Instant.parse("2026-10-07T10:00:00Z"));
        E2eTestService service = new E2eTestService(new E2eTestService.Dependencies(root,
            "acme", new DevelopmentGuard(new TargetResolver(List.of(DEV)), Map.of(DEV, dev)::get,
                node -> { }), node -> port, node -> logs, node -> processes,
            node -> Duration.ofMinutes(15),
            node -> new ImportService.Account("jdoe", "inubit-dev-1.example.test"),
            new WriteChallengeRegistry(clock), record -> { }, clock, UUID::randomUUID));
        McpTestClient client = McpTestClient.start(List.of(new RunE2eTestTool(service)));
        closeables.add(client);
        client.initialize();
        return client;
    }

    @Test
    void theToolIsDestructiveWithTheContractDescriptionAndSchema() throws IOException {
        JsonNode tool = client().listTools().path("tools").get(0);

        assertThat(tool.path("name").asString()).isEqualTo("run_e2e_test");
        assertThat(tool.path("description").asString()).isEqualTo(DESCRIPTION);
        assertThat(tool.path("annotations").path("destructiveHint").asBoolean()).isTrue();
        assertThat(tool.path("annotations").path("idempotentHint").asBoolean()).isFalse();
        JsonNode schema = tool.path("inputSchema");
        assertThat(schema.path("required")).extracting(JsonNode::asString)
            .containsExactlyInAnyOrder("node", "envelope", "path");
        JsonNode properties = schema.path("properties");
        assertThat(properties.path("timeoutSeconds").path("minimum").asInt()).isEqualTo(1);
        assertThat(properties.path("timeoutSeconds").path("maximum").asInt()).isEqualTo(120);
        assertThat(properties.path("includeExcerpt").path("type").asString())
            .isEqualTo("boolean");
        assertThat(properties.has("password")).isFalse();
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
    }

    @Test
    void theRunIsStructuredContentWithDefaults() throws IOException {
        JsonNode result = client().callTool("run_e2e_test", Map.of("node", "dev/node1",
            "envelope", "samples/order.xml", "path", "/ibis/ws/Service-01"));

        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        JsonNode run = result.path("structuredContent").path("result");
        assertThat(run.path("status").asInt()).isEqualTo(200);
        assertThat(run.path("correlation").asString()).isEqualTo("TIME_WINDOW_UNCERTAIN");
        assertThat(run.path("responseFile").asString()).startsWith(".tests/e2e/");
        assertThat(run.has("excerpt")).isFalse();
        assertThat(sent).singleElement().satisfies(message ->
            assertThat(message.timeout()).isEqualTo(Duration.ofSeconds(60)));
    }
}
