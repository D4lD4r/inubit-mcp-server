package de.dadecker.inubit.mcp.mcp.tools;

import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.assertMatchesOutputSchema;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.nodetools.SetLevelRequest;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.node.LeaseAttempt;
import de.dadecker.inubit.mcp.domain.model.node.LeaseReason;
import de.dadecker.inubit.mcp.domain.model.node.LeaseState;
import de.dadecker.inubit.mcp.domain.model.node.LeaseSummary;
import de.dadecker.inubit.mcp.domain.model.node.LogLevelResult;
import de.dadecker.inubit.mcp.domain.model.node.LoggerName;
import de.dadecker.inubit.mcp.domain.model.node.TemporaryLevel;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.mcp.SchemaResources;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Feature 024 T054: {@code set_temporary_log_level} over MCP (contracts/mcp-tools-delta.md,
 * FR-003, FR-040, research D-11, D-25) — the contract's description and destructive annotations,
 * the input schema refusing bad loggers, levels, durations and codes before the service, the
 * arguments and the MCP client reaching the service, and the challenge and result shapes
 * validating against {@code set_temporary_log_level.output.json}. The service is replaced by a
 * function returning a prepared {@link LogLevelResult}; nothing is sent anywhere.
 */
@Timeout(60)
class SetTemporaryLogLevelToolTest {

    /** contracts/mcp-tools-delta.md "Description", copied here to pin the wording. */
    private static final String CONTRACT_DESCRIPTION = "Raises the log level of one to five INUBIT"
        + " loggers (names below com.inubit.ibis) on the INUBIT node one {node} reaches, for a"
        + " limited time (default 10 minutes, at most 60), through StartCLI's documented trace"
        + " --nodelevel command; then download the trace log with download_server_log. The MCP"
        + " server records a lease and resets the node when it expires, when the MCP server shuts"
        + " down, or at its next start, also when write access is switched off meanwhile. The reset"
        + " (StartCLI trace --reset) restores every temporary log level on that node, also those"
        + " other users set; levels can also be lost earlier (INUBIT restart, log configuration"
        + " reload). Only the exactly named logger changes. Confirmation as restart_process."
        + " UNSUPPORTED: uses unsupported, undocumented INUBIT functionality (the lookup of the"
        + " answering node goes through an internal INUBIT interface) without vendor support or"
        + " stability guarantee, for diagnosis only; it may stop working after any INUBIT update."
        + " Needs write access and unsupported.logLevelWrite. Address cluster nodes directly, not"
        + " through a load balancer.";
    /** contracts/mcp-tools-delta.md "Output", the challenge example verbatim. */
    private static final String CONTRACT_CHALLENGE = """
        { "challenge": {
            "confirmationCode": "Zk3p0Qx8LmN2vB7cR1tY4w", "expiresAt": "2026-11-12T08:20:30Z",
            "preview": { "node": "test/node1", "inubitNode": "node1",
              "loggers": [{ "name": "com.inubit.ibis.acme.Orders", "level": "DEBUG" }],
              "durationMinutes": 10, "openLease": null },
            "message": "Nothing was changed. Call set_temporary_log_level again with confirmationCode before 2026-11-12T08:20:30Z to raise the level; the later reset restores every temporary level on node1, also other users'" } }""";
    /** contracts/mcp-tools-delta.md "Output", the result example verbatim. */
    private static final String CONTRACT_RESULT = """
        { "result": {
            "node": "test/node1", "inubitNode": "node1", "outcome": "EXECUTED",
            "leaseId": "3f2b8a1e-0c4d-4e7a-9b1f-2a6c5d8e9f01",
            "loggers": [{ "name": "com.inubit.ibis.acme.Orders", "level": "DEBUG" }],
            "expiresAt": "2026-11-12T08:25:31Z",
            "message": "Raised on INUBIT node node1 until 2026-11-12T08:25:31Z; then the MCP server resets every temporary log level on node1, also other users'. Levels can be lost earlier (INUBIT restart, log configuration reload).",
            "auditId": "8c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f",
            "warnings": [] } }""";
    private static final NodeId TEST = NodeId.parse("test/node1");
    private static final String ORDERS = "com.inubit.ibis.acme.Orders";
    private static final UUID LEASE = UUID.fromString("3f2b8a1e-0c4d-4e7a-9b1f-2a6c5d8e9f01");
    private static final UUID AUDIT = UUID.fromString("8c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f");
    private static final String CODE = "Zk3p0Qx8LmN2vB7cR1tY4w";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<SetLevelRequest> requests = new CopyOnWriteArrayList<>();
    private LogLevelResult result;
    private McpTestClient client;

    @BeforeEach
    void setUp() {
        result = executed();
        SecretScrubber scrubber = new SecretScrubber();
        scrubber.register(TestSecrets.CONFIGURED_PASSWORD);
        client = McpTestClient.start(List.of(new SetTemporaryLogLevelTool(request -> {
            requests.add(request);
            return result;
        })), scrubber);
        client.initialize();
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    // --- announcement -------------------------------------------------------------------------

    @Test
    void theToolIsADestructiveToolWithTheContractDescriptionAndSchema() {
        JsonNode tool = client.listTools().path("tools").get(0);

        assertThat(tool.path("name").asString()).isEqualTo("set_temporary_log_level");
        assertThat(SetTemporaryLogLevelTool.DESCRIPTION).isEqualTo(CONTRACT_DESCRIPTION);
        assertThat(tool.path("description").asString())
            .isEqualTo("[acme] " + Terminology.DEFAULT.render(CONTRACT_DESCRIPTION));
        JsonNode annotations = tool.path("annotations");
        assertThat(annotations.path("title").asString())
            .isEqualTo("Raise INUBIT log levels temporarily");
        assertThat(annotations.path("readOnlyHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("destructiveHint").asBoolean(false)).isTrue();
        assertThat(annotations.path("idempotentHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("openWorldHint").asBoolean(false)).isTrue();
        JsonNode schema = tool.path("inputSchema");
        assertThat(schema.path("required")).extracting(JsonNode::asString)
            .containsExactly("node", "loggers", "level");
        assertThat(schema.path("properties").propertyNames()).containsExactly("node", "loggers",
            "level", "durationMinutes", "confirmationCode");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        JsonNode loggers = schema.path("properties").path("loggers");
        assertThat(loggers.path("minItems").asInt()).isEqualTo(1);
        assertThat(loggers.path("maxItems").asInt()).isEqualTo(5);
        assertThat(loggers.path("uniqueItems").asBoolean(false)).isTrue();
        assertThat(schema.path("properties").path("level").path("enum"))
            .extracting(JsonNode::asString)
            .containsExactly("TRACE", "DEBUG", "INFO", "WARN", "ERROR");
        assertThat(schema.path("properties").path("durationMinutes").path("default").asInt())
            .isEqualTo(10);
    }

    @Test
    void theDescriptionCarriesTheFr003SentenceAndTheResetOfOtherUsersLevels() {
        assertThat(SetTemporaryLogLevelTool.DESCRIPTION).contains(
            "unsupported, undocumented INUBIT functionality",
            "without vendor support or stability guarantee", "for diagnosis only",
            "every temporary log level on that node, also those other users set",
            "UNSUPPORTED:", "any INUBIT update", "Needs write access and unsupported.logLevelWrite",
            "Confirmation as restart_process");
    }

    // --- input schema -------------------------------------------------------------------------

    @Test
    void theSchemaRefusesBadInputsBeforeTheService() {
        List<Map<String, Object>> calls = new ArrayList<>(List.of(
            with("node", "test"),
            with("node", "test/node1/x"),
            with("loggers", List.of()),
            with("loggers", List.of(ORDERS + "1", ORDERS + "2", ORDERS + "3", ORDERS + "4",
                ORDERS + "5", ORDERS + "6")),
            with("loggers", List.of(ORDERS, ORDERS)),
            with("loggers", List.of("org.apache.http")),
            with("loggers", List.of("com.inubit.ibis")),
            with("loggers", List.of("com.inubit.ibisx.acme")),
            with("loggers", List.of("com.inubit.ibis.acme.Orders$Inner")),
            with("loggers", List.of("com.inubit.ibis.acme.1Orders")),
            with("loggers", List.of("com.inubit.ibis.acme..Orders")),
            with("loggers", List.of("com.inubit.ibis.acme Orders")),
            with("loggers", List.of("com.inubit.ibis." + "a".repeat(185))),
            with("loggers", ORDERS),
            with("level", "debug"),
            with("level", "FATAL"),
            with("level", "OFF"),
            with("level", "ALL"),
            with("durationMinutes", 0),
            with("durationMinutes", 61),
            with("durationMinutes", -1),
            with("durationMinutes", 1.5),
            with("durationMinutes", "10"),
            with("confirmationCode", "short"),
            with("confirmationCode", CODE + "x"),
            with("confirmationCode", "Zk3p0Qx8LmN2vB7cR1tY4!"),
            with("unknown", true)));
        for (String required : List.of("node", "loggers", "level")) {
            Map<String, Object> without = valid();
            without.remove(required);
            calls.add(without);
        }

        for (Map<String, Object> arguments : calls) {
            JsonNode response = client.callTool("set_temporary_log_level", arguments);
            assertThat(response.path("isError").asBoolean()).as(arguments.toString()).isTrue();
            assertThat(response.has("structuredContent")).isFalse();
            assertThat(response.path("content").get(0).path("text").asString())
                .as("the schema, not the service's ToolError: %s", arguments)
                .doesNotContain("\"code\"");
        }
        assertThat(requests).as("the service was not called").isEmpty();
    }

    @Test
    void theLimitsOfTheSchemaPass() {
        List<Map<String, Object>> calls = List.of(
            with("loggers", List.of(ORDERS + "1", ORDERS + "2", ORDERS + "3", ORDERS + "4",
                ORDERS + "5")),
            with("loggers", List.of("com.inubit.ibis._x.Y_1")),
            with("loggers", List.of("com.inubit.ibis." + "a".repeat(184))),
            with("durationMinutes", 1),
            with("durationMinutes", 60),
            with("confirmationCode", CODE),
            with("level", "TRACE"),
            with("level", "INFO"),
            with("level", "WARN"),
            with("level", "ERROR"));

        for (Map<String, Object> arguments : calls) {
            JsonNode response = client.callTool("set_temporary_log_level", arguments);
            assertThat(response.path("isError").asBoolean()).as(arguments.toString()).isFalse();
        }
        assertThat(requests).hasSize(calls.size());
    }

    // --- arguments ----------------------------------------------------------------------------

    @Test
    void theArgumentsAndTheMcpClientReachTheService() {
        client.callTool("set_temporary_log_level", valid());
        Map<String, Object> full = valid();
        full.put("loggers", List.of(ORDERS, "com.inubit.ibis.acme.Billing"));
        full.put("durationMinutes", 30);
        full.put("confirmationCode", CODE);
        client.callTool("set_temporary_log_level", full);

        assertThat(requests).containsExactly(
            new SetLevelRequest("test/node1", List.of(ORDERS), "DEBUG", OptionalInt.empty(),
                Optional.empty(), Optional.of("mcp-test-client/1.0")),
            new SetLevelRequest("test/node1", List.of(ORDERS, "com.inubit.ibis.acme.Billing"),
                "DEBUG", OptionalInt.of(30), Optional.of(CODE),
                Optional.of("mcp-test-client/1.0")));
    }

    // --- output variants ----------------------------------------------------------------------

    @Test
    void aChallengeHasTheContractShapeWithoutAnOpenLease() {
        result = new LogLevelResult.Challenge(CODE, Instant.parse("2026-11-12T08:20:30Z"),
            new LogLevelResult.Preview(TEST, "node1", List.of(logger(ORDERS)), 10,
                Optional.empty()),
            "Nothing was changed. Call set_temporary_log_level again with confirmationCode before"
                + " 2026-11-12T08:20:30Z to raise the level; the later reset restores every"
                + " temporary level on node1, also other users'");

        JsonNode content = call();

        ObjectNode expected = (ObjectNode) json(CONTRACT_CHALLENGE);
        ((ObjectNode) expected.path("challenge").path("preview")).remove("openLease");
        assertThat(content).as("an absent openLease is omitted").isEqualTo(expected);
        assertThat(content.path("challenge").path("preview").propertyNames())
            .containsExactly("node", "inubitNode", "loggers", "durationMinutes");
    }

    @Test
    void aChallengeShowsTheOpenLeaseWithItsLastAttempt() {
        result = new LogLevelResult.Challenge(CODE, Instant.parse("2026-11-12T08:20:30Z"),
            new LogLevelResult.Preview(TEST, "node1", List.of(logger(ORDERS)), 10,
                Optional.of(openLease())),
            "Nothing was changed.");

        JsonNode openLease = call().path("challenge").path("preview").path("openLease");

        assertThat(openLease).isEqualTo(json("""
            { "leaseId": "3f2b8a1e-0c4d-4e7a-9b1f-2a6c5d8e9f01", "inubitNode": "node1",
              "loggers": [{ "name": "com.inubit.ibis.acme.Billing", "level": "TRACE" },
                          { "name": "com.inubit.ibis.acme.Orders", "level": "DEBUG" }],
              "setAt": "2026-11-12T08:15:31Z", "expiresAt": "2026-11-12T08:25:31Z",
              "state": "RESET_FAILED",
              "lastAttempt": { "at": "2026-11-12T08:26:01Z", "outcome": "FAILED",
                "code": "UNREACHABLE", "reason": "node unreachable" } }"""));
    }

    @Test
    void anExecutedSetHasTheContractShape() {
        JsonNode content = call();

        assertThat(content).isEqualTo(json(CONTRACT_RESULT));
        assertThat(content.path("result").propertyNames()).containsExactly("node", "inubitNode",
            "outcome", "leaseId", "loggers", "expiresAt", "message", "auditId", "warnings");
    }

    @Test
    void aFailedSetWithAnEndedLeaseHasNoExpiry() {
        result = new LogLevelResult.Outcome(TEST, "node1", LogLevelResult.Status.FAILED,
            Optional.of(LEASE), List.of(logger(ORDERS)), Optional.empty(), false,
            "StartCLI failed; the node was reset at once", AUDIT,
            List.of("StartCLI client 8.1.16 differs from INUBIT 8.1.17"));

        JsonNode content = call().path("result");

        assertThat(content.path("outcome").asString()).isEqualTo("FAILED");
        assertThat(content.has("expiresAt")).isFalse();
        assertThat(content.path("leaseId").asString()).isEqualTo(LEASE.toString());
        assertThat(content.path("warnings")).extracting(JsonNode::asString)
            .containsExactly("StartCLI client 8.1.16 differs from INUBIT 8.1.17");
    }

    @Test
    void aConfiguredCredentialInTheMessageIsScrubbed() {
        result = new LogLevelResult.Outcome(TEST, "node1", LogLevelResult.Status.FAILED,
            Optional.empty(), List.of(), Optional.empty(), false,
            "StartCLI said password=" + TestSecrets.CONFIGURED_PASSWORD, AUDIT, List.of());

        JsonNode response = client.callTool("set_temporary_log_level", valid());

        assertMatchesOutputSchema("set_temporary_log_level", response);
        TestSecrets.assertNoLeak(response.toString());
    }

    @Test
    void theContractExamplesMatchTheOutputSchema() {
        assertThat(errors(map(CONTRACT_CHALLENGE))).isNull();
        assertThat(errors(map(CONTRACT_RESULT))).isNull();
        Map<String, Object> both = new HashMap<>(map(CONTRACT_CHALLENGE));
        both.putAll(map(CONTRACT_RESULT));
        assertThat(errors(both)).as("challenge or result, not both").isNotNull();
        assertThat(errors(Map.of())).isNotNull();
        Map<String, Object> unknownField = new HashMap<>(map(CONTRACT_RESULT));
        @SuppressWarnings("unchecked")
        Map<String, Object> inner = new HashMap<>((Map<String, Object>) unknownField.get("result"));
        inner.put("raw", "x");
        unknownField.put("result", inner);
        assertThat(errors(unknownField)).isNotNull();
    }

    // --- helpers ------------------------------------------------------------------------------

    /** Calls the tool with valid arguments; the result validates against the output schema. */
    private JsonNode call() {
        JsonNode response = client.callTool("set_temporary_log_level", valid());
        assertMatchesOutputSchema("set_temporary_log_level", response);
        return response.path("structuredContent");
    }

    private static LogLevelResult executed() {
        return new LogLevelResult.Outcome(TEST, "node1", LogLevelResult.Status.EXECUTED,
            Optional.of(LEASE), List.of(logger(ORDERS)),
            Optional.of(Instant.parse("2026-11-12T08:25:31Z")), true,
            "Raised on INUBIT node node1 until 2026-11-12T08:25:31Z; then the MCP server resets"
                + " every temporary log level on node1, also other users'. Levels can be lost"
                + " earlier (INUBIT restart, log configuration reload).",
            AUDIT, List.of());
    }

    static LeaseSummary openLease() {
        return new LeaseSummary(LEASE, "node1",
            List.of(logger(ORDERS), new LeaseSummary.Logger(
                new LoggerName("com.inubit.ibis.acme.Billing"), TemporaryLevel.TRACE)),
            Instant.parse("2026-11-12T08:15:31Z"), Instant.parse("2026-11-12T08:25:31Z"),
            LeaseState.RESET_FAILED, Optional.of(new LeaseAttempt(
                Instant.parse("2026-11-12T08:26:01Z"), AuditOutcome.FAILED,
                Optional.of(ErrorCode.UNREACHABLE), Optional.of(LeaseReason.NODE_UNREACHABLE))));
    }

    private static LeaseSummary.Logger logger(String name) {
        return new LeaseSummary.Logger(new LoggerName(name), TemporaryLevel.DEBUG);
    }

    private static Map<String, Object> valid() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("node", "test/node1");
        arguments.put("loggers", List.of(ORDERS));
        arguments.put("level", "DEBUG");
        return arguments;
    }

    private static Map<String, Object> with(String key, Object value) {
        Map<String, Object> arguments = valid();
        arguments.put(key, value);
        return arguments;
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(String text) {
        return JSON.readValue(text, Map.class);
    }

    /** The validation errors of {@code result}, or {@code null} if it is valid. */
    private static String errors(Map<String, Object> result) {
        Map<String, Object> schema = map(SchemaResources.forClasspath()
            .load("schemas/set_temporary_log_level.output.json"));
        JsonSchemaValidator.ValidationResponse validation =
            new DefaultJsonSchemaValidator().validate(schema, result);
        return validation.valid() ? null : validation.errorMessage();
    }
}
