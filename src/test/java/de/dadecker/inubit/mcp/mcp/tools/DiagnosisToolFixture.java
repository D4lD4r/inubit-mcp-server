package de.dadecker.inubit.mcp.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.AdapterGatewayFactory;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.SystemProcessLauncher;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.application.DiagnosisService;
import de.dadecker.inubit.mcp.application.FanOut;
import de.dadecker.inubit.mcp.application.ResultLimiter;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.config.CredentialResolver;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.VersionLine;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.infra.ResultJson;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.mcp.SchemaResources;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The US2 tools with the production adapters: {@code dev/node1} and {@code qa/node1} on
 * HTTPS WireMock servers, {@code qa/node2} unreachable ({@code 127.0.0.1:9}).
 */
final class DiagnosisToolFixture implements AutoCloseable {

    static final String PASSWORD = "diagnosis-tool-test-pw";
    static final Duration TIMEOUT = Duration.ofSeconds(2);
    static final JsonMapper JSON = JsonMapper.builder().build();

    final SecretScrubber scrubber = new SecretScrubber();
    final AdapterGatewayFactory gateways;
    final McpTestClient client;

    DiagnosisToolFixture(WireMockServer dev, WireMockServer integration,
        Function<DiagnosisService, ToolHandler> tool) {
        List<EffectiveNodeConfig> servers = List.of(
            server("dev/node1", "https://localhost:" + dev.httpsPort()),
            server("qa/node1", "https://localhost:" + integration.httpsPort()),
            server("qa/node2", "https://127.0.0.1:9"));
        List<NodeId> ids = servers.stream().map(EffectiveNodeConfig::id).toList();
        gateways = new AdapterGatewayFactory(servers, new CredentialResolver(Map.of(
            "INUBIT_DEV_USERNAME", "jdoe", "INUBIT_DEV_PASSWORD", PASSWORD,
            "INUBIT_QA_USERNAME", "jdoe", "INUBIT_QA_PASSWORD", PASSWORD), scrubber, "INUBIT")
            .resolve(ids), scrubber, Clock.systemUTC(),
            new CliRunner(new SystemProcessLauncher(), Map.of(), false,
                new CliResources("acme")));
        Map<NodeId, Duration> timeouts = servers.stream().collect(Collectors.toMap(
            EffectiveNodeConfig::id, EffectiveNodeConfig::timeout));
        Map<NodeId, Duration> thresholds = servers.stream().collect(Collectors.toMap(
            EffectiveNodeConfig::id, EffectiveNodeConfig::hangingThreshold));
        DiagnosisService diagnosis = new DiagnosisService(gateways, new TargetResolver(ids),
            new FanOut(), ResultLimiter.withDefaults(), timeouts::get, thresholds::get,
            Clock.systemUTC(), ResultJson::size);
        client = McpTestClient.start(List.of(tool.apply(diagnosis)), scrubber);
        client.initialize();
    }

    private static EffectiveNodeConfig server(String id, String baseUrl) {
        return TestNodeConfig.node().id(id).baseUrl(baseUrl)
            .trustStore(TestCertificates.get().trustStore()).timeout(TIMEOUT)
            .versionLine(VersionLine.AUTO).build();
    }

    /** The parsed {@code {"error": …}} of a tool error result. */
    static JsonNode toolError(JsonNode result) {
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isTrue();
        assertThat(result.has("structuredContent")).isFalse();
        return JSON.readTree(result.path("content").get(0).path("text").asString())
            .path("error");
    }

    static void assertMatchesOutputSchema(String tool, JsonNode result) {
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = JSON.readValue(SchemaResources.forClasspath()
            .load("schemas/" + tool + ".output.json"), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> content = JSON.treeToValue(result.path("structuredContent"),
            Map.class);
        JsonSchemaValidator.ValidationResponse validation =
            new DefaultJsonSchemaValidator().validate(schema, content);
        assertThat(validation.valid()).as(validation.errorMessage()).isTrue();
        assertThat(JSON.readTree(result.path("content").get(0).path("text").asString()))
            .isEqualTo(result.path("structuredContent"));
    }

    @Override
    public void close() {
        client.close();
        gateways.close();
    }
}
