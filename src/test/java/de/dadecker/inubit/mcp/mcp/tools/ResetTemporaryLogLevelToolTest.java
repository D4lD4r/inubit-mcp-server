package de.dadecker.inubit.mcp.mcp.tools;

import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.assertMatchesOutputSchema;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.nodetools.ResetLevelRequest;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.node.LogLevelResult;
import de.dadecker.inubit.mcp.domain.model.node.LeaseSummary;
import de.dadecker.inubit.mcp.domain.model.node.LoggerName;
import de.dadecker.inubit.mcp.domain.model.node.TemporaryLevel;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.mcp.SchemaResources;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feature 024 T054: {@code reset_temporary_log_level} over MCP (contracts/mcp-tools-delta.md,
 * FR-003, FR-044, research D-15, D-25) — the contract's description and destructive annotations,
 * the input schema admitting exactly its two forms ({@code node}, or {@code leaseId} with
 * {@code forget: true}) before the service, the arguments and the MCP client reaching the service,
 * and the challenge and result shapes validating against
 * {@code reset_temporary_log_level.output.json}. The service is replaced by a function returning a
 * prepared {@link LogLevelResult}; nothing is sent anywhere.
 */
@Timeout(60)
class ResetTemporaryLogLevelToolTest {

    /** contracts/mcp-tools-delta.md "Description", copied here to pin the wording. */
    private static final String CONTRACT_DESCRIPTION = "Resets every temporary log level on the"
        + " INUBIT node one {node} reaches through StartCLI's documented trace -r command (every"
        + " temporary log level on that node, also those other users set), and ends the MCP"
        + " server's lease for it; works also without a lease (to clean up an earlier change). With"
        + " leaseId and forget: true it instead ends a lease whose automatic reset failed, without"
        + " contacting INUBIT; the level may then stay raised until that node restarts."
        + " Confirmation as restart_process. UNSUPPORTED: uses unsupported, undocumented INUBIT"
        + " functionality (the lookup of the answering node goes through an internal INUBIT"
        + " interface) without vendor support or stability guarantee, for diagnosis only; it may"
        + " stop working after any INUBIT update. Needs write access and"
        + " unsupported.logLevelWrite. Address cluster nodes directly, not through a load"
        + " balancer.";
    /** contracts/mcp-tools-delta.md "Output", the result example verbatim. */
    private static final String CONTRACT_RESULT = """
        { "result": {
            "node": "test/node1", "inubitNode": "node1", "outcome": "EXECUTED",
            "leaseId": "3f2b8a1e-0c4d-4e7a-9b1f-2a6c5d8e9f01", "leaseOpen": true,
            "message": "Every temporary log level on INUBIT node node1 was reset, also other users'; the lease ended",
            "auditId": "…", "warnings": [] } }""";
    private static final NodeId TEST = NodeId.parse("test/node1");
    private static final String LEASE_ID = "3f2b8a1e-0c4d-4e7a-9b1f-2a6c5d8e9f01";
    private static final UUID LEASE = UUID.fromString(LEASE_ID);
    private static final UUID AUDIT = UUID.fromString("8c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f");
    private static final String CODE = "Zk3p0Qx8LmN2vB7cR1tY4w";
    private static final String CLIENT = "mcp-test-client/1.0";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<ResetLevelRequest> requests = new CopyOnWriteArrayList<>();
    private LogLevelResult result;
    private McpTestClient client;

    @BeforeEach
    void setUp() {
        result = outcome(Optional.of(LEASE), true,
            "Every temporary log level on INUBIT node node1 was reset, also other users'; the"
                + " lease ended");
        client = McpTestClient.start(List.of(new ResetTemporaryLogLevelTool(request -> {
            requests.add(request);
            return result;
        })), new SecretScrubber());
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

        assertThat(tool.path("name").asString()).isEqualTo("reset_temporary_log_level");
        assertThat(ResetTemporaryLogLevelTool.DESCRIPTION).isEqualTo(CONTRACT_DESCRIPTION);
        assertThat(tool.path("description").asString())
            .isEqualTo("[acme] " + Terminology.DEFAULT.render(CONTRACT_DESCRIPTION));
        JsonNode annotations = tool.path("annotations");
        assertThat(annotations.path("title").asString())
            .isEqualTo("Reset temporary INUBIT log levels");
        assertThat(annotations.path("readOnlyHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("destructiveHint").asBoolean(false)).isTrue();
        assertThat(annotations.path("idempotentHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("openWorldHint").asBoolean(false)).isTrue();
        JsonNode schema = tool.path("inputSchema");
        assertThat(schema.has("required")).isFalse();
        assertThat(schema.path("properties").propertyNames()).containsExactly("node", "leaseId",
            "forget", "confirmationCode");
        assertThat(schema.path("oneOf")).hasSize(2);
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
    }

    @Test
    void theDescriptionCarriesTheFr003SentenceAndTheResetOfOtherUsersLevels() {
        assertThat(ResetTemporaryLogLevelTool.DESCRIPTION).contains(
            "unsupported, undocumented INUBIT functionality",
            "without vendor support or stability guarantee", "for diagnosis only",
            "every temporary log level on that node, also those other users set",
            "UNSUPPORTED:", "any INUBIT update", "Needs write access and unsupported.logLevelWrite",
            "Confirmation as restart_process", "without contacting INUBIT");
    }

    // --- input schema: the two forms ----------------------------------------------------------

    @Test
    void theSchemaAdmitsOnlyTheTwoFormsBeforeTheService() {
        List<Map<String, Object>> calls = List.of(
            Map.of(),
            Map.of("confirmationCode", CODE),
            Map.of("node", "test/node1", "leaseId", LEASE_ID),
            Map.of("node", "test/node1", "forget", true),
            Map.of("node", "test/node1", "leaseId", LEASE_ID, "forget", true),
            Map.of("leaseId", LEASE_ID),
            Map.of("forget", true),
            Map.of("leaseId", LEASE_ID, "forget", false),
            Map.of("leaseId", LEASE_ID, "forget", "true"),
            Map.of("leaseId", LEASE_ID.toUpperCase(Locale.ROOT), "forget", true),
            Map.of("leaseId", "3f2b8a1e0c4d4e7a9b1f2a6c5d8e9f01", "forget", true),
            Map.of("leaseId", "lease-1", "forget", true),
            Map.of("node", "test"),
            Map.of("node", "test/node1/x"),
            Map.of("node", "test/node1", "confirmationCode", "short"),
            Map.of("node", "test/node1", "unknown", true));

        for (Map<String, Object> arguments : calls) {
            JsonNode response = client.callTool("reset_temporary_log_level", arguments);
            assertThat(response.path("isError").asBoolean()).as(arguments.toString()).isTrue();
            assertThat(response.has("structuredContent")).isFalse();
            assertThat(response.path("content").get(0).path("text").asString())
                .as("the schema, not the service's ToolError: %s", arguments)
                .doesNotContain("\"code\"");
        }
        assertThat(requests).as("the service was not called").isEmpty();
    }

    @Test
    void bothFormsReachTheServiceWithTheMcpClient() {
        List<Map<String, Object>> calls = List.of(
            Map.of("node", "test/node1"),
            Map.of("node", "test/node1", "confirmationCode", CODE),
            Map.of("leaseId", LEASE_ID, "forget", true),
            Map.of("leaseId", LEASE_ID, "forget", true, "confirmationCode", CODE));

        for (Map<String, Object> arguments : calls) {
            JsonNode response = client.callTool("reset_temporary_log_level", arguments);
            assertThat(response.path("isError").asBoolean()).as(arguments.toString()).isFalse();
        }

        assertThat(requests).containsExactly(
            new ResetLevelRequest(Optional.of("test/node1"), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(CLIENT)),
            new ResetLevelRequest(Optional.of("test/node1"), Optional.empty(), Optional.empty(),
                Optional.of(CODE), Optional.of(CLIENT)),
            new ResetLevelRequest(Optional.empty(), Optional.of(LEASE_ID), Optional.of(true),
                Optional.empty(), Optional.of(CLIENT)),
            new ResetLevelRequest(Optional.empty(), Optional.of(LEASE_ID), Optional.of(true),
                Optional.of(CODE), Optional.of(CLIENT)));
    }

    // --- output variants ----------------------------------------------------------------------

    @Test
    void aResetThatEndedALeaseHasTheContractShapeWithoutLoggers() {
        JsonNode content = call(Map.of("node", "test/node1"));

        JsonNode expected = json(CONTRACT_RESULT.replace("\"…\"", "\"" + AUDIT + "\""));
        assertThat(content).isEqualTo(expected);
        assertThat(content.path("result").propertyNames()).containsExactly("node", "inubitNode",
            "outcome", "leaseId", "leaseOpen", "message", "auditId", "warnings");
    }

    @Test
    void aResetWithoutALeaseSaysSo() {
        result = outcome(Optional.empty(), false, "No lease was open for test/node1; every"
            + " temporary log level on INUBIT node node1 was reset, also other users'");

        JsonNode content = call(Map.of("node", "test/node1")).path("result");

        assertThat(content.has("leaseId")).isFalse();
        assertThat(content.path("leaseOpen").asBoolean(true)).isFalse();
        assertThat(content.path("message").asString()).startsWith("No lease was open");
    }

    @Test
    void aResetChallengeShowsTheOpenLeaseOrNone() {
        result = new LogLevelResult.Challenge(CODE, Instant.parse("2026-11-12T08:20:30Z"),
            new LogLevelResult.ResetPreview(TEST, "node1",
                Optional.of(SetTemporaryLogLevelToolTest.openLease())),
            "Nothing was changed.");

        JsonNode preview = call(Map.of("node", "test/node1")).path("challenge").path("preview");

        assertThat(preview.propertyNames()).containsExactly("node", "inubitNode", "openLease");
        assertThat(preview.path("openLease").path("state").asString()).isEqualTo("RESET_FAILED");
        assertThat(preview.path("openLease").path("lastAttempt").path("reason").asString())
            .isEqualTo("node unreachable");

        result = new LogLevelResult.Challenge(CODE, Instant.parse("2026-11-12T08:20:30Z"),
            new LogLevelResult.ResetPreview(TEST, "node1", Optional.empty()),
            "Nothing was changed.");

        assertThat(call(Map.of("node", "test/node1")).path("challenge").path("preview")
            .propertyNames()).containsExactly("node", "inubitNode");
    }

    @Test
    void theOutcomeIgnoresTheLoggersAndTheExpiryOfTheEndedLease() {
        result = new LogLevelResult.Outcome(TEST, "node1", LogLevelResult.Status.EXECUTED,
            Optional.of(LEASE), List.of(new LeaseSummary.Logger(
                new LoggerName("com.inubit.ibis.acme.Orders"), TemporaryLevel.DEBUG)),
            Optional.of(Instant.parse("2026-11-12T08:25:31Z")), true,
            "Lease 3f2b8a1e ended as FORGOTTEN without contacting INUBIT; the level may still be"
                + " raised on node1 until its restart", AUDIT, List.of());

        JsonNode content = call(Map.of("leaseId", LEASE_ID, "forget", true)).path("result");

        assertThat(content.has("loggers")).isFalse();
        assertThat(content.has("expiresAt")).isFalse();
        assertThat(content.path("outcome").asString()).isEqualTo("EXECUTED");
    }

    @Test
    void theContractExampleMatchesTheOutputSchema() {
        Map<String, Object> example = map(CONTRACT_RESULT);

        assertThat(errors(example)).isNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> withLoggers = new HashMap<>((Map<String, Object>) example.get("result"));
        withLoggers.put("loggers", List.of());
        assertThat(errors(Map.of("result", withLoggers))).as("no loggers field").isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> withoutLeaseOpen =
            new HashMap<>((Map<String, Object>) example.get("result"));
        withoutLeaseOpen.remove("leaseOpen");
        assertThat(errors(Map.of("result", withoutLeaseOpen))).as("leaseOpen required")
            .isNotNull();
    }

    // --- helpers ------------------------------------------------------------------------------

    /** Calls the tool; the result validates against the output schema. */
    private JsonNode call(Map<String, Object> arguments) {
        JsonNode response = client.callTool("reset_temporary_log_level", arguments);
        assertMatchesOutputSchema("reset_temporary_log_level", response);
        return response.path("structuredContent");
    }

    private static LogLevelResult outcome(Optional<UUID> leaseId, boolean leaseOpen,
        String message) {
        return new LogLevelResult.Outcome(TEST, "node1", LogLevelResult.Status.EXECUTED, leaseId,
            List.of(), Optional.empty(), leaseOpen, message, AUDIT, List.of());
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
            .load("schemas/reset_temporary_log_level.output.json"));
        JsonSchemaValidator.ValidationResponse validation =
            new DefaultJsonSchemaValidator().validate(schema, result);
        return validation.valid() ? null : validation.errorMessage();
    }
}
