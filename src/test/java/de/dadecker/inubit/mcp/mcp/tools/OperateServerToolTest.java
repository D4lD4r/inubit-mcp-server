package de.dadecker.inubit.mcp.mcp.tools;

import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.assertMatchesOutputSchema;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.application.ChangeChallenges;
import de.dadecker.inubit.mcp.application.ServerOperationService.Request;
import de.dadecker.inubit.mcp.application.ServerOperationService.Response;
import de.dadecker.inubit.mcp.application.WriteChallengeRegistry;
import de.dadecker.inubit.mcp.domain.model.ChangeOutcome;
import de.dadecker.inubit.mcp.domain.model.ChangePreview;
import de.dadecker.inubit.mcp.domain.model.ChangeResult;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.TraceLevel;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.mcp.SchemaResources;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feature 022 T061: {@code operate_server} over MCP (contracts/mcp-tools-delta.md "Common rules"
 * and "operate_server", data-model.md "ChangePreview", "ChangeResult", spec US4) — the
 * description and destructive annotations, the input schema refusing bad actions and fields before the
 * service, the arguments and the MCP client reaching the service, the preview and result shapes
 * validating against {@code operate_server.output.json}, and the {@code CONFLICT} error carrying
 * the fresh preview in its details. The service is replaced by a function returning a prepared
 * {@link Response}; nothing is sent anywhere.
 */
@Timeout(60)
class OperateServerToolTest {

    /** The description, written from the contract and spec US4, copied here to pin the wording. */
    private static final String DESCRIPTION = "Operate ONE INUBIT {node} (`node`:"
        + " `<{group}>/<{node}>`) with one `action`: setMaintenance (`on`; with `on: true`"
        + " optionally `executeQueued`, sent as -eq: whether the processes already queued are"
        + " still executed), setReadiness (`ready`: what the readiness probe reports to a load"
        + " balancer, READY or NOT READY, without stopping the process engine), setTrace (`on`),"
        + " setTraceLevel (`level`), triggerMidnightTask, deleteErrorProcesses."
        + " In maintenance mode the process engine executes no processes and INUBIT's REST API"
        + " answers every request with \"service unavailable\"; the readiness stays NOT READY"
        + " while maintenance mode is on. Tracing and the trace level stay as set until they are"
        + " set back. The midnight task is the daily task INUBIT otherwise starts"
        + " at its configured start time; triggering it runs it at once, and the vendor does not"
        + " document what it does. deleteErrorProcesses deletes every process in state ERROR;"
        + " this cannot be undone. Every call needs a `reason` (stored in the audit log). On"
        + " {nodes} with `confirmationMode` `SERVER` (the default), the first call only returns a"
        + " preview (state before, requested state, effects) and a confirmation code; call again"
        + " with the same inputs and `confirmationCode` to execute. If the state changed"
        + " meanwhile, the CONFLICT error carries a new preview in its details. With `CLIENT`"
        + " (never on production) the first call executes. The result re-reads the state. Needs"
        + " write access; production only with write.productionOptIn.";
    private static final List<String> ACTIONS = List.of("setMaintenance", "setReadiness",
        "setTrace", "setTraceLevel", "triggerMidnightTask", "deleteErrorProcesses");
    private static final NodeId TEST = NodeId.parse("test/node1");
    private static final String CODE = "Zk3p0Qx8LmN2vB7cR1tY4w";
    private static final String REASON = "Prepare the maintenance window";
    private static final UUID AUDIT = UUID.fromString("7f0c2a8e-1b3d-4c5e-9f6a-0b1c2d3e4f50");
    private static final Instant EXPIRES = Instant.parse("2026-11-18T08:20:30Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private Supplier<Response> response;
    private McpTestClient client;

    @BeforeEach
    void setUp() {
        response = () -> new Response.Preview(maintenancePreview());
        SecretScrubber scrubber = new SecretScrubber();
        scrubber.register(TestSecrets.CONFIGURED_PASSWORD);
        client = McpTestClient.start(List.of(new OperateServerTool(request -> {
            requests.add(request);
            return response.get();
        })), scrubber);
        client.initialize();
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    // --- announcement -------------------------------------------------------------------------

    @Test
    void theToolIsADestructiveToolWithItsDescriptionAndSchema() {
        JsonNode tool = client.listTools().path("tools").get(0);

        assertThat(tool.path("name").asString()).isEqualTo("operate_server");
        assertThat(OperateServerTool.DESCRIPTION).isEqualTo(DESCRIPTION);
        assertThat(tool.path("description").asString())
            .isEqualTo("[acme] " + Terminology.DEFAULT.render(DESCRIPTION));
        JsonNode annotations = tool.path("annotations");
        assertThat(annotations.path("title").asString())
            .isEqualTo(Terminology.DEFAULT.render("Operate an INUBIT {node}"));
        assertThat(annotations.path("readOnlyHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("destructiveHint").asBoolean(false)).isTrue();
        assertThat(annotations.path("idempotentHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("openWorldHint").asBoolean(false)).isTrue();
        JsonNode schema = tool.path("inputSchema");
        assertThat(schema.path("required")).extracting(JsonNode::asString)
            .containsExactly("node", "action", "reason");
        assertThat(schema.path("properties").propertyNames()).containsExactly("node", "action",
            "on", "executeQueued", "ready", "level", "reason", "confirmationCode");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(schema.path("properties").path("action").path("enum"))
            .extracting(JsonNode::asString).containsExactlyElementsOf(ACTIONS);
        assertThat(schema.path("properties").path("level").path("enum"))
            .extracting(JsonNode::asString).containsExactlyElementsOf(Arrays.stream(
                TraceLevel.values()).map(level -> level.name().toLowerCase(Locale.ROOT)).toList());
    }

    /** Spec US4: AS5, AS9, AS10, the midnight task and the maintenance mode. */
    @Test
    void theDescriptionSaysWhatTheSpecRequires() {
        assertThat(OperateServerTool.DESCRIPTION).contains(
            "the vendor does not document what it does",
            "Tracing and the trace level stay as set until they are set back",
            "the readiness stays NOT READY while maintenance mode is on",
            "INUBIT's REST API answers every request with \"service unavailable\"",
            "runs it at once", "this cannot be undone", "the first call only returns a preview",
            "the CONFLICT error carries a new preview in its details");
    }

    // --- input schema -------------------------------------------------------------------------

    @Test
    void theSchemaRefusesBadInputsBeforeTheService() {
        List<Map<String, Object>> calls = new ArrayList<>(List.of(
            with(trace(), "node", "test"),
            with(trace(), "node", "test/node1/x"),
            with(trace(), "action", "restart"),
            with(trace(), "reason", ""),
            with(trace(), "reason", "first line\nsecond line"),
            with(trace(), "reason", "x".repeat(501)),
            with(trace(), "confirmationCode", "short"),
            with(trace(), "confirmationCode", CODE + "x"),
            with(trace(), "unknown", true),
            with(trace(), "on", "true"),
            with(trace(), "level", "debug"),
            with(trace(), "executeQueued", true),
            with(trace(), "ready", true),
            call("setTrace", Map.of()),
            call("setMaintenance", Map.of()),
            call("setMaintenance", Map.of("on", false, "executeQueued", true)),
            call("setMaintenance", Map.of("on", false, "executeQueued", false)),
            call("setMaintenance", Map.of("on", true, "ready", false)),
            call("setReadiness", Map.of()),
            call("setReadiness", Map.of("on", true)),
            call("setReadiness", Map.of("ready", true, "on", true)),
            call("setTraceLevel", Map.of()),
            call("setTraceLevel", Map.of("level", "DEBUG")),
            call("setTraceLevel", Map.of("level", "trace")),
            call("setTraceLevel", Map.of("level", "debug", "on", true)),
            call("triggerMidnightTask", Map.of("on", true)),
            call("deleteErrorProcesses", Map.of("level", "error"))));
        for (String required : List.of("node", "action", "reason")) {
            Map<String, Object> without = trace();
            without.remove(required);
            calls.add(without);
        }

        for (Map<String, Object> arguments : calls) {
            JsonNode result = client.callTool("operate_server", arguments);
            assertThat(result.path("isError").asBoolean()).as(arguments.toString()).isTrue();
            assertThat(result.has("structuredContent")).isFalse();
            assertThat(result.path("content").get(0).path("text").asString())
                .as("the schema, not the service's ToolError: %s", arguments)
                .doesNotContain("\"code\"");
        }
        assertThat(requests).as("the service was not called").isEmpty();
    }

    /** T054: {@code debug on|off} had no observable server effect; it is no action (AS8). */
    @Test
    void setDebugIsAnUnknownActionAndNeverReachesTheService() {
        JsonNode result = client.callTool("operate_server", call("setDebug", Map.of("on", true)));

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").get(0).path("text").asString())
            .as("the schema, not the service's ToolError").doesNotContain("\"code\"");
        assertThat(OperateServerTool.DESCRIPTION).doesNotContain("setDebug", "debug output");
        assertThat(requests).as("the service was not called").isEmpty();
    }

    @Test
    void everyActionFormAndTheLimitsPassTheSchema() {
        List<Map<String, Object>> calls = new ArrayList<>(List.of(
            call("setMaintenance", Map.of("on", true)),
            call("setMaintenance", Map.of("on", true, "executeQueued", true)),
            call("setMaintenance", Map.of("on", true, "executeQueued", false)),
            call("setMaintenance", Map.of("on", false)),
            call("setReadiness", Map.of("ready", true)),
            call("setReadiness", Map.of("ready", false)),
            call("setTrace", Map.of("on", false)),
            call("triggerMidnightTask", Map.of()),
            call("deleteErrorProcesses", Map.of()),
            with(trace(), "confirmationCode", CODE),
            with(trace(), "reason", "x".repeat(500))));
        for (TraceLevel level : TraceLevel.values()) {
            calls.add(call("setTraceLevel", Map.of("level",
                level.name().toLowerCase(Locale.ROOT))));
        }

        for (Map<String, Object> arguments : calls) {
            JsonNode result = client.callTool("operate_server", arguments);
            assertThat(result.path("isError").asBoolean()).as(arguments.toString()).isFalse();
        }
        assertThat(requests).hasSize(calls.size());
    }

    // --- arguments ----------------------------------------------------------------------------

    @Test
    void theActionFieldsTheReasonTheCodeAndTheMcpClientReachTheService() {
        client.callTool("operate_server", call("setMaintenance",
            Map.of("on", true, "executeQueued", false)));
        client.callTool("operate_server", with(call("setTraceLevel", Map.of("level", "debug")),
            "confirmationCode", CODE));
        client.callTool("operate_server", call("deleteErrorProcesses", Map.of()));

        assertThat(requests).containsExactly(
            new Request("test/node1", "setMaintenance", Map.of("on", true, "executeQueued", false),
                REASON, Optional.empty(), Optional.of("mcp-test-client/1.0")),
            new Request("test/node1", "setTraceLevel", Map.of("level", "debug"), REASON,
                Optional.of(CODE), Optional.of("mcp-test-client/1.0")),
            new Request("test/node1", "deleteErrorProcesses", Map.of(), REASON,
                Optional.empty(), Optional.of("mcp-test-client/1.0")));
    }

    // --- output -------------------------------------------------------------------------------

    @Test
    void aPreviewHasTheChangePreviewShape() {
        JsonNode preview = structured(client.callTool("operate_server", trace())).path("preview");

        assertThat(preview.path("node").asString()).isEqualTo("test/node1");
        assertThat(preview.path("tool").asString()).isEqualTo("operate_server");
        assertThat(preview.path("action").asString()).isEqualTo("setMaintenance");
        assertThat(preview.path("area").asString()).isEqualTo("operation");
        assertThat(preview.path("target").asString()).isEqualTo("maintenance mode");
        assertThat(preview.path("before").asString())
            .isEqualTo("off (2 processes running, 5 queued)");
        assertThat(preview.path("requested").asString()).isEqualTo("on, -eq false");
        assertThat(preview.path("effects")).extracting(JsonNode::asString).hasSize(2);
        assertThat(preview.path("backup").asString())
            .isEqualTo("none: nothing is removed or replaced");
        assertThat(preview.path("confirmation").path("code").asString()).isEqualTo(CODE);
        assertThat(preview.path("confirmation").path("expiresAt").asString())
            .isEqualTo("2026-11-18T08:20:30Z");
        assertThat(preview.has("affected")).isFalse();
        assertThat(preview.path("warnings").isArray()).isTrue();
    }

    /** AS4: the count and up to 50 ids; the workflows concerned are named in {@code before}. */
    @Test
    void aDeletePreviewNamesTheAffectedProcesses() {
        response = () -> new Response.Preview(deletePreview(Optional.of(new ChangePreview.Affected(
            40, List.of("110219899", "110219900"), true))));

        JsonNode affected = structured(client.callTool("operate_server",
            call("deleteErrorProcesses", Map.of()))).path("preview").path("affected");

        assertThat(affected.path("count").asInt()).isEqualTo(40);
        assertThat(affected.path("ids")).extracting(JsonNode::asString)
            .containsExactly("110219899", "110219900");
        assertThat(affected.path("truncated").asBoolean()).isTrue();
    }

    /** {@code write.confirmation: CLIENT}: a preview without code. */
    @Test
    void aPreviewWithoutCodeHasTheSameShape() {
        response = () -> new Response.Preview(deletePreview(Optional.of(
            new ChangePreview.Affected(1200, List.of(), true))));

        JsonNode preview = structured(client.callTool("operate_server",
            call("deleteErrorProcesses", Map.of()))).path("preview");

        assertThat(preview.has("confirmation")).isFalse();
    }

    @Test
    void anExecutedResultCarriesTheReReadStateAndTheWarnings() {
        response = () -> new Response.Completed(new ChangeResult(TEST, "operate_server",
            "setTraceLevel", "trace level", ChangeOutcome.EXECUTED, "error", "trace level debug",
            Optional.of("debug"), Optional.empty(), OptionalInt.empty(), Optional.empty(),
            ChangeResult.RestartNeeded.FALSE, AUDIT),
            List.of("StartCLI client 8.1.16 differs from INUBIT 8.1.17"));

        JsonNode content = structured(client.callTool("operate_server",
            call("setTraceLevel", Map.of("level", "debug"))));

        JsonNode result = content.path("result");
        assertThat(result.path("node").asString()).isEqualTo("test/node1");
        assertThat(result.path("tool").asString()).isEqualTo("operate_server");
        assertThat(result.path("action").asString()).isEqualTo("setTraceLevel");
        assertThat(result.path("target").asString()).isEqualTo("trace level");
        assertThat(result.path("outcome").asString()).isEqualTo("EXECUTED");
        assertThat(result.path("before").asString()).isEqualTo("error");
        assertThat(result.path("after").asString()).isEqualTo("debug");
        assertThat(result.path("inubitAnswer").asString()).isEqualTo("trace level debug");
        assertThat(result.path("restartNeeded").asString()).isEqualTo("FALSE");
        assertThat(result.path("auditId").asString()).isEqualTo(AUDIT.toString());
        assertThat(result.has("reReadFailed")).isFalse();
        assertThat(result.has("affectedCount")).isFalse();
        assertThat(result.has("backup")).isFalse();
        assertThat(content.path("warnings")).extracting(JsonNode::asString)
            .containsExactly("StartCLI client 8.1.16 differs from INUBIT 8.1.17");
    }

    @Test
    void aFailedReReadAndANothingToDoHaveTheChangeResultShape() {
        response = () -> new Response.Completed(new ChangeResult(TEST, "operate_server",
            "deleteErrorProcesses", "processes in state ERROR", ChangeOutcome.EXECUTED,
            "40 processes in state ERROR", "Process with status [error] has been deleted.",
            Optional.empty(), Optional.of("MAINTENANCE_MODE: INUBIT is in maintenance mode"),
            OptionalInt.empty(), Optional.empty(), ChangeResult.RestartNeeded.FALSE, AUDIT),
            List.of());

        JsonNode failed = structured(client.callTool("operate_server",
            call("deleteErrorProcesses", Map.of())));

        assertThat(failed.path("result").path("inubitAnswer").asString())
            .isEqualTo("Process with status [error] has been deleted.");
        assertThat(failed.path("result").has("affectedCount")).isFalse();
        assertThat(failed.path("result").path("reReadFailed").asString()).isNotEmpty();
        assertThat(failed.path("result").has("after")).isFalse();
        assertThat(failed.path("warnings").isEmpty()).isTrue();

        response = () -> new Response.Completed(new ChangeResult(TEST, "operate_server",
            "deleteErrorProcesses", "processes in state ERROR", ChangeOutcome.NOTHING_TO_DO,
            "no process in state ERROR", "nothing was sent", Optional.of(
                "no process in state ERROR"), Optional.empty(), OptionalInt.empty(),
            Optional.empty(), ChangeResult.RestartNeeded.FALSE, AUDIT), List.of());

        assertThat(structured(client.callTool("operate_server", call("deleteErrorProcesses",
            Map.of()))).path("result").path("outcome").asString()).isEqualTo("NOTHING_TO_DO");
    }

    // --- errors -------------------------------------------------------------------------------

    /**
     * contracts/mcp-tools-delta.md "Errors": {@code CONFLICT} (state changed; details carry the
     * new preview). The conflict comes from {@link ChangeChallenges#confirmOrConflict}, as in the
     * service.
     */
    @Test
    void aConflictCarriesTheFreshPreviewWithItsNewCodeInTheErrorDetails() {
        ChangeChallenges challenges = new ChangeChallenges(new WriteChallengeRegistry(
            Clock.fixed(Instant.parse("2026-11-18T08:10:30Z"), ZoneOffset.UTC)));
        ChangeChallenges.Conflict conflict = catchThrowableOfType(ChangeChallenges.Conflict.class,
            () -> challenges.confirmOrConflict("count 40", "count 41", "fingerprint",
                deletePreview(Optional.of(new ChangePreview.Affected(41, List.of(), true))),
                Duration.ofMinutes(10)));
        response = () -> {
            throw conflict;
        };

        JsonNode result = client.callTool("operate_server", with(call("deleteErrorProcesses",
            Map.of()), "confirmationCode", CODE));

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.has("structuredContent")).isFalse();
        JsonNode error = JSON.readTree(result.path("content").get(0).path("text").asString())
            .path("error");
        assertThat(error.path("code").asString()).isEqualTo("CONFLICT");
        assertThat(error.path("node").asString()).isEqualTo("test/node1");
        assertThat(error.path("nextStep").asString()).contains("new preview in the error details");
        JsonNode preview = error.path("details").path("preview");
        assertThat(preview.path("action").asString()).isEqualTo("deleteErrorProcesses");
        assertThat(preview.path("affected").path("count").asInt()).isEqualTo(41);
        assertThat(preview.path("confirmation").path("code").asString())
            .isEqualTo(conflict.preview().confirmation().orElseThrow().code())
            .isNotEqualTo(CODE).matches("^[A-Za-z0-9_-]{22}$");
        assertThat(preview.path("confirmation").path("expiresAt").asString())
            .isEqualTo("2026-11-18T08:20:30Z");
        @SuppressWarnings("unchecked")
        Map<String, Object> asOutput = Map.of("preview", JSON.treeToValue(preview, Map.class));
        assertThat(errors(asOutput)).as("the preview of the output schema").isNull();
    }

    @Test
    void anyOtherRefusalHasNoDetails() {
        response = () -> {
            throw new ToolErrorException(ToolError.of(ErrorCode.WRITE_DISABLED,
                "Write access is disabled", "The test group is read-only",
                "Set write.enabled: true").withNode(TEST));
        };

        JsonNode result = client.callTool("operate_server", trace());

        JsonNode error = JSON.readTree(result.path("content").get(0).path("text").asString())
            .path("error");
        assertThat(error.path("code").asString()).isEqualTo("WRITE_DISABLED");
        assertThat(error.has("details")).isFalse();
    }

    // --- leak check (SC-005) ------------------------------------------------------------------

    @Test
    void noSecretLeavesThroughAPreviewAResultOrAConflict() {
        String planted = "password=" + TestSecrets.CONFIGURED_PASSWORD + " "
            + TestSecrets.FREE_TEXT_SECRETS;
        response = () -> new Response.Preview(new ChangePreview(TEST, "operate_server",
            "setMaintenance", "operation", "maintenance mode", "off " + planted, "on " + planted,
            Optional.empty(), List.of("effect " + TestSecrets.CONFIGURED_PASSWORD),
            "none: nothing is removed or replaced", Optional.empty(),
            List.of("warning " + TestSecrets.CONFIGURED_PASSWORD)));
        JsonNode preview = client.callTool("operate_server", trace());
        assertMatchesOutputSchema("operate_server", preview);

        response = () -> new Response.Completed(new ChangeResult(TEST, "operate_server",
            "setMaintenance", "maintenance mode", ChangeOutcome.FAILED, "off " + planted,
            "StartCLI said " + planted, Optional.empty(), Optional.of("re-read " + planted),
            OptionalInt.empty(), Optional.empty(), ChangeResult.RestartNeeded.FALSE, AUDIT),
            List.of("warning " + TestSecrets.CONFIGURED_PASSWORD));
        JsonNode result = client.callTool("operate_server", trace());
        assertMatchesOutputSchema("operate_server", result);

        ChangeChallenges challenges = new ChangeChallenges(new WriteChallengeRegistry(
            Clock.systemUTC()));
        ChangeChallenges.Conflict conflict = catchThrowableOfType(ChangeChallenges.Conflict.class,
            () -> challenges.confirmOrConflict("off", "on", "fingerprint", new ChangePreview(TEST,
                "operate_server", "setMaintenance", "operation", "maintenance mode",
                "on " + planted, "on", Optional.empty(), List.of("effect "
                    + TestSecrets.CONFIGURED_PASSWORD), "none: nothing is removed or replaced",
                Optional.empty(), List.of()), Duration.ofMinutes(10)));
        response = () -> {
            throw conflict;
        };
        JsonNode refused = client.callTool("operate_server", with(trace(), "confirmationCode",
            CODE));
        assertThat(refused.path("content").get(0).path("text").asString())
            .contains("\"details\"");

        for (JsonNode text : List.of(preview, result, refused)) {
            TestSecrets.assertNoLeak(text.toString());
        }
    }

    // --- output schema ------------------------------------------------------------------------

    @Test
    void theOutputSchemaTakesAPreviewOrAResultNotBoth() {
        @SuppressWarnings("unchecked")
        Map<String, Object> preview = JSON.treeToValue(structured(client.callTool(
            "operate_server", trace())), Map.class);
        assertThat(errors(preview)).isNull();

        assertThat(errors(Map.of())).isNotNull();
        assertThat(errors(Map.of("preview", Map.of()))).as("an empty preview").isNotNull();
        Map<String, Object> both = new HashMap<>(preview);
        both.put("result", Map.of());
        assertThat(errors(both)).isNotNull();
        Map<String, Object> withWarnings = new HashMap<>(preview);
        withWarnings.put("warnings", List.of());
        assertThat(errors(withWarnings)).as("warnings belong to a result").isNotNull();
    }

    // --- helpers ------------------------------------------------------------------------------

    /** The structured content of {@code result}, validated against the output schema. */
    private static JsonNode structured(JsonNode result) {
        assertMatchesOutputSchema("operate_server", result);
        return result.path("structuredContent");
    }

    private static ChangePreview maintenancePreview() {
        return new ChangePreview(TEST, "operate_server", "setMaintenance", "operation",
            "maintenance mode", "off (2 processes running, 5 queued)", "on, -eq false",
            Optional.empty(), List.of("In maintenance mode the process engine executes no"
                + " processes, and INUBIT's REST API answers every request with \"service"
                + " unavailable\".", "-eq false is sent: the processes already queued are not"
                + " executed while maintenance mode is on."),
            "none: nothing is removed or replaced",
            Optional.of(new ChangePreview.Confirmation(CODE, EXPIRES)), List.of());
    }

    private static ChangePreview deletePreview(Optional<ChangePreview.Affected> affected) {
        return new ChangePreview(TEST, "operate_server", "deleteErrorProcesses", "operation",
            "processes in state ERROR", "40 processes in state ERROR, workflows: acme-orders",
            "deleted", affected, List.of("The processes in state ERROR are deleted and cannot be"
                + " restored."), "none: the processes are not exported before they are deleted",
            Optional.empty(), List.of());
    }

    /** {@code setTrace on}, a valid call. */
    private static Map<String, Object> trace() {
        return call("setTrace", Map.of("on", true));
    }

    private static Map<String, Object> call(String action, Map<String, Object> fields) {
        Map<String, Object> arguments = new HashMap<>(fields);
        arguments.put("node", "test/node1");
        arguments.put("action", action);
        arguments.put("reason", REASON);
        return arguments;
    }

    private static Map<String, Object> with(Map<String, Object> arguments, String key,
        Object value) {
        Map<String, Object> changed = new HashMap<>(arguments);
        changed.put(key, value);
        return changed;
    }

    /** The validation errors of {@code result}, or {@code null} if it is valid. */
    @SuppressWarnings("unchecked")
    private static String errors(Map<String, Object> result) {
        Map<String, Object> schema = JSON.readValue(SchemaResources.forClasspath()
            .load("schemas/operate_server.output.json"), Map.class);
        JsonSchemaValidator.ValidationResponse validation =
            new DefaultJsonSchemaValidator().validate(schema, result);
        return validation.valid() ? null : validation.errorMessage();
    }
}
