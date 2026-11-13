package de.dadecker.inubit.mcp.mcp.tools;

import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.assertMatchesOutputSchema;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.ServerSettingsService.NodeSettings;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.ConfigurationView;
import de.dadecker.inubit.mcp.domain.model.DbConnectionTolerance;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.LicenseFacts;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeResult;
import de.dadecker.inubit.mcp.domain.model.PrivilegedWorkflows;
import de.dadecker.inubit.mcp.domain.model.SemVer;
import de.dadecker.inubit.mcp.domain.model.ServerSettings;
import de.dadecker.inubit.mcp.domain.model.ServerUpdate;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.UnavailablePart;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feature 022 T022 (US1, FR-009, FR-013, contracts/mcp-tools-delta.md "get_server_settings"):
 * {@code get_server_settings} over MCP — the contract's description, annotations and input
 * schema, the schema rejecting malformed calls before the service, the arguments reaching the
 * service, and the result shape {@code {settings: [ServerSettings], serverUpdate?}}, validating
 * against {@code get_server_settings.output.json}. A fake read records the calls and answers with
 * prepared node results; nothing is contacted.
 */
@Timeout(60)
class GetServerSettingsToolTest {

    /** contracts/mcp-tools-delta.md "Description", copied here to pin the wording. */
    private static final String CONTRACT_DESCRIPTION = "Read the settings of the INUBIT {nodes}:"
        + " license (expiry date, licensed threads, licensee), workflow threads, privileged"
        + " workflows, DB connection tolerance and the current time, and on request the"
        + " configuration. `target` takes the id of one {node} (`<{group}>/<{node}>`) or of one"
        + " {group} (one entry per {node}). `parts` picks what to read (default: all but"
        + " `configuration`). Without `configurationSection` the configuration is listed by"
        + " section names; with it, that section's values are shown, secrets masked. Version,"
        + " memory share, blocking queue size and maintenance mode are always included, from the"
        + " same source as get_health. License and configuration need the INUBIT role \"System"
        + " Administrator\"; a part that cannot be read is listed under `unavailable` with the"
        + " reason, the other parts are returned. Workflow threads and privileged workflows"
        + " need the INUBIT root user and are listed under `unavailable`. Reads only; changes"
        + " nothing on INUBIT.";
    private static final NodeId NODE1 = NodeId.parse("test/node1");
    private static final NodeId NODE2 = NodeId.parse("test/node2");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** One recorded call of the read. */
    private record Call(String target, Set<String> parts, Optional<String> section) {
    }

    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private volatile List<NodeResult<NodeSettings>> results = List.of();
    private volatile Optional<ServerUpdate> serverUpdate = Optional.empty();
    private McpTestClient client;

    @BeforeEach
    void setUp() {
        results = List.of(NodeResult.success(NODE1, new NodeSettings(full(NODE1), List.of())));
        client = McpTestClient.start(List.of(new GetServerSettingsTool((target, parts, section) -> {
            calls.add(new Call(target, parts, section));
            return results;
        }, () -> serverUpdate)), new SecretScrubber());
        client.initialize();
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    // --- announcement -------------------------------------------------------------------------

    @Test
    void theToolIsReadOnlyWithTheContractDescriptionAndSchema() {
        JsonNode tool = client.listTools().path("tools").get(0);

        assertThat(tool.path("name").asString()).isEqualTo("get_server_settings");
        assertThat(GetServerSettingsTool.DESCRIPTION).isEqualTo(CONTRACT_DESCRIPTION);
        assertThat(tool.path("description").asString())
            .isEqualTo("[acme] " + Terminology.DEFAULT.render(CONTRACT_DESCRIPTION));
        JsonNode annotations = tool.path("annotations");
        assertThat(annotations.path("title").asString()).isEqualTo("Read INUBIT settings");
        assertThat(annotations.path("readOnlyHint").asBoolean(false)).isTrue();
        assertThat(annotations.path("destructiveHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("idempotentHint").asBoolean(false)).isTrue();
        assertThat(annotations.path("openWorldHint").asBoolean(false)).isTrue();
        JsonNode schema = tool.path("inputSchema");
        assertThat(schema.path("required")).extracting(JsonNode::asString)
            .containsExactly("target");
        assertThat(schema.path("properties").propertyNames()).containsExactly("target", "parts",
            "configurationSection");
        assertThat(schema.path("properties").path("parts").path("items").path("enum"))
            .extracting(JsonNode::asString).containsExactly("license", "threads",
                "privilegedWorkflows", "dbConnectionTolerance", "configuration", "serverTime");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(schema.path("properties").path("target").path("description").asString())
            .isEqualTo("Id of one group (all its nodes) or of one node (`<group>/<node>`)");
    }

    @Test
    void theDescriptionStatesTheContractPoints() {
        String description = GetServerSettingsTool.DESCRIPTION.toLowerCase(Locale.ROOT);

        // reads only; the role; get_health's source; the section names without a section
        assertThat(description).contains("reads only", "changes nothing",
            "\"system administrator\"", "same source as get_health",
            "listed by section names", "secrets masked", "`unavailable`");
    }

    // --- input schema -------------------------------------------------------------------------

    @Test
    void theSchemaRejectsMalformedCallsBeforeTheService() {
        List<Map<String, Object>> calls = new ArrayList<>(List.of(
            Map.of(),
            Map.of("target", ""),
            Map.of("target", "Test"),
            Map.of("target", "test/node1/x"),
            with("parts", List.of()),
            with("parts", List.of("license", "license")),
            with("parts", List.of("version")),
            with("parts", List.of("status")),
            with("parts", "license"),
            with("configurationSection", ""),
            with("configurationSection", "x".repeat(201)),
            with("configurationSection", "a\nb"),
            with("configurationSection", "a\u0000b"),
            with("configurationSection", "a\u007Fb"),
            with("unknown", true)));

        for (Map<String, Object> arguments : calls) {
            JsonNode response = client.callTool("get_server_settings", arguments);
            assertThat(response.path("isError").asBoolean()).as(arguments.toString()).isTrue();
            assertThat(response.has("structuredContent")).isFalse();
            assertThat(response.path("content").get(0).path("text").asString())
                .as("the schema, not the service's ToolError: %s", arguments)
                .doesNotContain("\"code\"");
        }
        assertThat(this.calls).as("the service was not called").isEmpty();
    }

    @Test
    void theLimitsOfTheSchemaAreInclusive() {
        List<Map<String, Object>> arguments = List.of(
            Map.of("target", "test"),
            with("parts", List.of("license", "threads", "privilegedWorkflows",
                "dbConnectionTolerance", "configuration", "serverTime")),
            with("configurationSection", "x"),
            with("configurationSection", "ü".repeat(200)),
            with("configurationSection", "SMTP Properties"));

        for (Map<String, Object> call : arguments) {
            JsonNode response = client.callTool("get_server_settings", call);
            assertThat(response.path("isError").asBoolean()).as(call.toString()).isFalse();
        }
        assertThat(calls).hasSize(arguments.size());
    }

    // --- arguments ----------------------------------------------------------------------------

    @Test
    void everyArgumentReachesTheService() {
        client.callTool("get_server_settings", Map.of("target", "test",
            "parts", List.of("threads", "configuration"),
            "configurationSection", "SMTPProperties"));

        assertThat(calls).containsExactly(new Call("test", Set.of("threads", "configuration"),
            Optional.of("SMTPProperties")));
    }

    @Test
    void absentOptionalArgumentsAreLeftToTheService() {
        client.callTool("get_server_settings", Map.of("target", "test/node1"));

        // no parts: the service's default (all but the configuration)
        assertThat(calls).containsExactly(new Call("test/node1", Set.of(), Optional.empty()));
    }

    // --- output -------------------------------------------------------------------------------

    @Test
    void aServerWithEveryFactHasTheContractShape() {
        results = List.of(NodeResult.success(NODE1, new NodeSettings(full(NODE1),
            List.of("INUBIT 8.1.17 on test/node1: detected"))));

        JsonNode content = call();

        assertThat(content.propertyNames()).containsExactly("settings");
        JsonNode entry = content.path("settings").get(0);
        assertThat(entry.propertyNames()).containsExactly("node", "license", "workflowThreads",
            "privilegedWorkflows", "dbConnectionTolerance", "blockingQueueSize", "version",
            "memoryUsedPercent", "maintenanceMode", "serverTime", "configuration", "sources",
            "unavailable", "warnings");
        assertThat(entry).isEqualTo(json("""
            {"node": "test/node1",
             "license": {"expiryDate": "2027-03-31", "licensedThreads": 40,
                         "licensee": "ACME ***"},
             "workflowThreads": 30,
             "privilegedWorkflows": {"percentage": 20, "workflows": ["Order", "Invoice"],
                                     "truncated": false},
             "dbConnectionTolerance": {"faultThreshold": 3, "dropRatePercent": 10,
                                       "dropIntervalSeconds": 60},
             "blockingQueueSize": 200000,
             "version": "8.1.17",
             "memoryUsedPercent": 59.7,
             "maintenanceMode": false,
             "serverTime": "Thu Nov 12 09:00:00 CET 2026",
             "configuration": {"sections": ["SMTPProperties", "ProxySettings"], "maskedCount": 0},
             "sources": {"blockingQueueSize": "REST", "configuration": "REST",
                         "dbConnectionTolerance": "CLI", "license": "REST",
                         "licensedThreads": "REST", "maintenanceMode": "REST",
                         "memoryUsedPercent": "REST", "privilegedWorkflows": "CLI",
                         "serverTime": "CLI", "version": "REST", "workflowThreads": "CLI"},
             "unavailable": [],
             "warnings": ["INUBIT 8.1.17 on test/node1: detected"]}"""));
        assertThat(entry.path("sources").propertyNames()).as("sorted by field")
            .containsExactly("blockingQueueSize", "configuration", "dbConnectionTolerance",
                "license", "licensedThreads", "maintenanceMode", "memoryUsedPercent",
                "privilegedWorkflows", "serverTime", "version", "workflowThreads");
    }

    @Test
    void aSectionShowsItsValuesWithTheMaskedOnesMarked() {
        ConfigurationView section = ConfigurationView.section("SMTPProperties", List.of(
            ConfigurationView.Leaf.shown("/ErrorHost", "smtp.example.test"),
            ConfigurationView.Leaf.masked("/Password"),
            ConfigurationView.Leaf.shown("/Port", "null")), true);
        results = List.of(NodeResult.success(NODE1, new NodeSettings(new ServerSettings(NODE1,
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.of("8.1.17"), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.of(section),
            Map.of("version", Channel.REST, "configuration", Channel.REST), List.of()),
            List.of())));

        JsonNode entry = call().path("settings").get(0);

        assertThat(entry.path("configuration")).isEqualTo(json("""
            {"section": "SMTPProperties",
             "values": [{"path": "/ErrorHost", "value": "smtp.example.test"},
                        {"path": "/Password", "masked": true},
                        {"path": "/Port", "value": "null"}],
             "maskedCount": 1, "truncated": true}"""));
    }

    @Test
    void unavailablePartsCarryTheirReasonAndTheOtherPartsAreReturned() {
        // AS4: the missing role for GET /license keeps the /metrics threads and the other parts
        ServerSettings settings = new ServerSettings(NODE1,
            Optional.of(new LicenseFacts(Optional.empty(), Optional.of(40L), Optional.empty())),
            Optional.of(30L), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Map.of("licensedThreads", Channel.REST,
                "workflowThreads", Channel.CLI),
            List.of(UnavailablePart.of(UnavailablePart.LICENSE, ToolError.of(ErrorCode.FORBIDDEN,
                    "GET /license on test/node1 was refused", "The account lacks a role",
                    "Grant the role System Administrator")),
                new UnavailablePart(UnavailablePart.LOAD,
                    "METRICS_NOT_LICENSED: the license has no metrics")));
        results = List.of(NodeResult.success(NODE1, new NodeSettings(settings, List.of())));

        JsonNode entry = call().path("settings").get(0);

        assertThat(entry.path("license")).isEqualTo(json("{\"licensedThreads\": 40}"));
        assertThat(entry.path("unavailable")).isEqualTo(json("""
            [{"part": "license", "reason": "FORBIDDEN: GET /license on test/node1 was refused",
              "likelyCause": "The account lacks a role",
              "nextStep": "Grant the role System Administrator"},
             {"part": "load", "reason": "METRICS_NOT_LICENSED: the license has no metrics"}]"""));
        assertThat(entry.path("warnings")).isEmpty();
    }

    @Test
    void aStageGivesOneEntryPerServerAndAFailedServerOnlyItsError() {
        // AS5: one entry per server, in the order of the read; FR-013: a refused server
        ToolError refused = ToolError.of(ErrorCode.UNSUPPORTED_VERSION,
            "get_server_settings: INUBIT 9.0 on test/node2 is not yet verified",
            "The coverage list has no verified entry", "Use another node");
        results = List.of(NodeResult.success(NODE1, new NodeSettings(full(NODE1), List.of())),
            NodeResult.failure(NODE2, refused));

        JsonNode settings = call().path("settings");

        assertThat(settings).hasSize(2);
        assertThat(settings.get(0).path("node").asString()).isEqualTo("test/node1");
        assertThat(settings.get(1).propertyNames()).containsExactly("node", "error");
        assertThat(settings.get(1).path("node").asString()).isEqualTo("test/node2");
        assertThat(settings.get(1).path("error").path("code").asString())
            .isEqualTo("UNSUPPORTED_VERSION");
        assertThat(settings.get(1).path("error").path("message").asString())
            .isEqualTo("get_server_settings: INUBIT 9.0 on test/node2 is not yet verified");
    }

    @Test
    void theLastUpdateCheckIsReported() {
        assertThat(call().has("serverUpdate")).isFalse();

        serverUpdate = Optional.of(ServerUpdate.of(SemVer.parse("0.7.1").orElseThrow(),
            SemVer.parse("0.8.0").orElseThrow(), Instant.parse("2026-11-06T12:00:00Z")));
        JsonNode update = call().path("serverUpdate");

        assertThat(update.path("latestVersion").asString()).isEqualTo("0.8.0");
        assertThat(update.path("updateAvailable").asBoolean()).isTrue();
    }

    // --- helpers ------------------------------------------------------------------------------

    /** Calls the tool for {@code test}; the result validates against the output schema. */
    private JsonNode call() {
        JsonNode response = client.callTool("get_server_settings", Map.of("target", "test"));
        assertMatchesOutputSchema("get_server_settings", response);
        return response.path("structuredContent");
    }

    /** Every fact of US1 read, the configuration listed by section names. */
    static ServerSettings full(NodeId node) {
        Map<String, Channel> sources = new HashMap<>();
        for (String rest : List.of("license", "licensedThreads", "configuration", "version",
            "maintenanceMode", "blockingQueueSize", "memoryUsedPercent")) {
            sources.put(rest, Channel.REST);
        }
        for (String cli : List.of("workflowThreads", "privilegedWorkflows",
            "dbConnectionTolerance", "serverTime")) {
            sources.put(cli, Channel.CLI);
        }
        return new ServerSettings(node,
            Optional.of(new LicenseFacts(Optional.of(LocalDate.of(2027, 3, 31)), Optional.of(40L),
                Optional.of("ACME ***"))),
            Optional.of(30L), Optional.of(PrivilegedWorkflows.of(20, List.of("Order", "Invoice"))),
            Optional.of(new DbConnectionTolerance(3, 10, 60)), Optional.of(200_000L),
            Optional.of("8.1.17"), Optional.of(59.7), Optional.of(false),
            Optional.of("Thu Nov 12 09:00:00 CET 2026"),
            Optional.of(ConfigurationView.sections(List.of("SMTPProperties", "ProxySettings"))),
            sources, List.of());
    }

    private static Map<String, Object> with(String key, Object value) {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("target", "test/node1");
        arguments.put(key, value);
        return arguments;
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }
}
