package de.dadecker.inubit.mcp.mcp.tools;

import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.assertMatchesOutputSchema;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.application.ChangeChallenges;
import de.dadecker.inubit.mcp.application.TagAdministrationService.Request;
import de.dadecker.inubit.mcp.application.TagAdministrationService.Response;
import de.dadecker.inubit.mcp.application.WriteChallengeRegistry;
import de.dadecker.inubit.mcp.domain.model.ChangeOutcome;
import de.dadecker.inubit.mcp.domain.model.ChangePreview;
import de.dadecker.inubit.mcp.domain.model.ChangeResult;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.mcp.SchemaResources;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
 * Feature 022 T064: {@code manage_tags} over MCP (contracts/mcp-tools-delta.md "Common rules" and
 * "manage_tags", spec US4 AS12–AS14, FR-019a) — the description of the active-tag actions saying
 * that tag removal and deletion are not offered (excluded after T055), the destructive
 * annotations, the input schema refusing bad actions and fields before the service, the removed
 * actions {@code removeTag} and {@code deleteTag} included (AS13, AS14), the arguments and the MCP
 * client reaching the service, the preview and result shapes validating against
 * {@code manage_tags.output.json}, and the {@code CONFLICT} error carrying the fresh preview in its
 * details. The service is replaced by a function returning a prepared {@link Response}; nothing is
 * sent anywhere.
 */
@Timeout(60)
class ManageTagsToolTest {

    /** The start of every description, written from the contract and spec US4 AS12. */
    private static final String ACTIVE_TAG = "Manage the tags of ONE INUBIT {node} (`node`:"
        + " `<{group}>/<{node}>`) with one `action`: setActiveTag (`user`, `tag`) sets the active"
        + " tag of an INUBIT user, removeActiveTag (`user`) removes it; the preview names the"
        + " user's current and requested active tag, and the result reads it again. An unknown"
        + " user gives NOT_FOUND.";
    private static final String CONFIRMATION = " Every call needs a `reason` (stored in the"
        + " audit log). On {nodes} with `confirmationMode` `SERVER` (the default), the first call"
        + " only returns a preview and a confirmation code; call again with the same inputs and"
        + " `confirmationCode` to execute. If the state changed meanwhile, the CONFLICT error"
        + " carries a new preview in its details. With `CLIENT` (never on production) the first"
        + " call executes. Needs write access; production only with write.productionOptIn.";
    /** Tag removal and deletion are excluded after the live check T055 (research D-18). */
    private static final String DESCRIPTION = ACTIVE_TAG + " Not offered: removing or deleting a"
        + " tag. INUBIT deletes a tag for the whole owner even when one diagram group is named,"
        + " and removes it only in a repository path whose tagged files cannot be read for a"
        + " backup." + CONFIRMATION;
    private static final List<String> ACTIONS = List.of("setActiveTag", "removeActiveTag");
    private static final NodeId TEST = NodeId.parse("test/node1");
    private static final String CODE = "Zk3p0Qx8LmN2vB7cR1tY4w";
    private static final String REASON = "Clean up the release tags";
    private static final UUID AUDIT = UUID.fromString("7f0c2a8e-1b3d-4c5e-9f6a-0b1c2d3e4f50");
    private static final Instant EXPIRES = Instant.parse("2026-11-18T08:20:30Z");
    private static final Map<String, Object> GROUP = Map.of("diagramGroup", "Orders",
        "diagramType", "technical");
    private static final Map<String, Object> REMOVAL = Map.of("tag", "REL-1", "scope", GROUP);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private Supplier<Response> response;
    private McpTestClient client;

    @BeforeEach
    void setUp() {
        response = () -> new Response.Preview(activePreview());
        SecretScrubber scrubber = new SecretScrubber();
        scrubber.register(TestSecrets.CONFIGURED_PASSWORD);
        client = McpTestClient.start(List.of(new ManageTagsTool(request -> {
            requests.add(request);
            return response.get();
        })), scrubber);
        client.initialize();
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    // --- announcement and description ---------------------------------------------------------

    @Test
    void theToolIsADestructiveToolWithItsDescriptionAndSchema() {
        JsonNode tool = client.listTools().path("tools").get(0);

        assertThat(tool.path("name").asString()).isEqualTo("manage_tags");
        assertThat(tool.path("description").asString())
            .isEqualTo("[acme] " + Terminology.DEFAULT.render(DESCRIPTION));
        JsonNode annotations = tool.path("annotations");
        assertThat(annotations.path("title").asString())
            .isEqualTo(Terminology.DEFAULT.render("Manage the tags of an INUBIT {node}"));
        assertThat(annotations.path("readOnlyHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("destructiveHint").asBoolean(false)).isTrue();
        assertThat(annotations.path("idempotentHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("openWorldHint").asBoolean(false)).isTrue();
        JsonNode schema = tool.path("inputSchema");
        assertThat(schema.path("required")).extracting(JsonNode::asString)
            .containsExactly("node", "action", "reason");
        assertThat(schema.path("properties").propertyNames()).containsExactly("node", "action",
            "user", "tag", "reason", "confirmationCode");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(schema.path("properties").path("action").path("enum"))
            .extracting(JsonNode::asString).containsExactlyElementsOf(ACTIONS);
    }

    @Test
    void theAnnotationsAreThoseOfAChangeTool() {
        assertThat(new ManageTagsTool(request -> {
            throw new AssertionError("not called");
        }).annotations())
            .isEqualTo(ToolHints.destructive("Manage the tags of an INUBIT {node}"));
    }

    // --- input schema -------------------------------------------------------------------------

    @Test
    void theSchemaRefusesBadInputsBeforeTheService() {
        List<Map<String, Object>> calls = new ArrayList<>(List.of(
            with(active(), "node", "test"),
            with(active(), "action", "renameTag"),
            with(active(), "reason", ""),
            with(active(), "reason", "first line\nsecond line"),
            with(active(), "confirmationCode", "short"),
            with(active(), "unknown", true),
            with(active(), "user", "-jdoe"),
            with(active(), "tag", ""),
            with(active(), "scope", GROUP),
            call("setActiveTag", Map.of("user", "jdoe")),
            call("removeActiveTag", Map.of()),
            call("removeActiveTag", Map.of("user", "jdoe", "tag", "REL-1")),
            // AS13, AS14: tag removal and deletion are no actions (excluded after T055)
            call("removeTag", REMOVAL),
            call("removeTag", Map.of("tag", "REL-1", "scope", Map.of("repositoryPath",
                "/Root/acme/mappings"))),
            call("removeTag", Map.of("tag", "REL-1")),
            call("deleteTag", REMOVAL),
            call("deleteTag", Map.of("tag", "REL-1"))));
        for (String required : List.of("node", "action", "reason")) {
            Map<String, Object> without = active();
            without.remove(required);
            calls.add(without);
        }

        for (Map<String, Object> arguments : calls) {
            JsonNode result = client.callTool("manage_tags", arguments);
            assertThat(result.path("isError").asBoolean()).as(arguments.toString()).isTrue();
            assertThat(result.has("structuredContent")).isFalse();
            assertThat(result.path("content").get(0).path("text").asString())
                .as("the schema, not the service's ToolError: %s", arguments)
                .doesNotContain("\"code\"");
        }
        assertThat(requests).as("the service was not called").isEmpty();
    }

    @Test
    void everyActionFormPassesTheSchema() {
        List<Map<String, Object>> calls = List.of(
            active(),
            call("removeActiveTag", Map.of("user", "jdoe")),
            with(active(), "confirmationCode", CODE));

        for (Map<String, Object> arguments : calls) {
            JsonNode result = client.callTool("manage_tags", arguments);
            assertThat(result.path("isError").asBoolean()).as(arguments.toString()).isFalse();
        }
        assertThat(requests).hasSize(calls.size());
    }

    // --- arguments ----------------------------------------------------------------------------

    @Test
    void theActionFieldsTheReasonTheCodeAndTheMcpClientReachTheService() {
        client.callTool("manage_tags", active());
        client.callTool("manage_tags", with(call("removeActiveTag", Map.of("user", "jdoe")),
            "confirmationCode", CODE));

        assertThat(requests).containsExactly(
            new Request("test/node1", "setActiveTag", Map.of("user", "jdoe", "tag", "REL-2"),
                REASON, Optional.empty(), Optional.of("mcp-test-client/1.0")),
            new Request("test/node1", "removeActiveTag", Map.of("user", "jdoe"), REASON,
                Optional.of(CODE), Optional.of("mcp-test-client/1.0")));
    }

    // --- output -------------------------------------------------------------------------------

    @Test
    void anActiveTagPreviewHasTheChangePreviewShape() {
        JsonNode preview = structured(client.callTool("manage_tags", active())).path("preview");

        assertThat(preview.path("tool").asString()).isEqualTo("manage_tags");
        assertThat(preview.path("action").asString()).isEqualTo("setActiveTag");
        assertThat(preview.path("area").asString()).isEqualTo("operation");
        assertThat(preview.has("affected")).isFalse();
        assertThat(preview.path("backup").asString()).startsWith("none: ");
        assertThat(preview.path("confirmation").path("code").asString()).isEqualTo(CODE);
    }

    @Test
    void anActiveTagResultNamesTheReReadTagWithoutABackup() {
        response = () -> new Response.Completed(new ChangeResult(TEST, "manage_tags",
            "setActiveTag", "active tag of the user jdoe", ChangeOutcome.EXECUTED,
            "active tag REL-1", "tag --tagSetActive was sent; StartCLI reported no error",
            Optional.of("active tag REL-2"), Optional.empty(), OptionalInt.empty(),
            Optional.empty(), ChangeResult.RestartNeeded.FALSE, AUDIT), List.of());

        JsonNode result = structured(client.callTool("manage_tags", active())).path("result");

        assertThat(result.path("outcome").asString()).isEqualTo("EXECUTED");
        assertThat(result.path("after").asString()).isEqualTo("active tag REL-2");
        assertThat(result.has("backup")).isFalse();
        assertThat(result.has("affectedCount")).isFalse();
    }

    @Test
    void anActiveTagResultWithAFailedReReadHasTheChangeResultShape() {
        response = () -> new Response.Completed(new ChangeResult(TEST, "manage_tags",
            "setActiveTag", "active tag of the user jdoe", ChangeOutcome.FAILED,
            "active tag REL-1", "UNEXPECTED_RESPONSE: StartCLI answered n-NOK", Optional.empty(),
            Optional.of("TIMEOUT: StartCLI did not answer"), OptionalInt.empty(),
            Optional.empty(), ChangeResult.RestartNeeded.FALSE, AUDIT),
            List.of("StartCLI client 8.1.16 differs from INUBIT 8.1.17"));

        JsonNode content = structured(client.callTool("manage_tags", active()));

        assertThat(content.path("result").path("reReadFailed").asString()).isNotEmpty();
        assertThat(content.path("result").has("after")).isFalse();
        assertThat(content.path("warnings")).extracting(JsonNode::asString).hasSize(1);
    }

    // --- errors -------------------------------------------------------------------------------

    /** A {@code CONFLICT} carries its fresh preview without a catch in the tool. */
    @Test
    void aConflictCarriesTheFreshPreviewWithItsNewCodeInTheErrorDetails() {
        ChangeChallenges challenges = new ChangeChallenges(new WriteChallengeRegistry(
            Clock.fixed(Instant.parse("2026-11-18T08:10:30Z"), ZoneOffset.UTC)));
        ChangeChallenges.Conflict conflict = catchThrowableOfType(ChangeChallenges.Conflict.class,
            () -> challenges.confirmOrConflict("setActiveTag=a", "setActiveTag=b", "fingerprint",
                activePreview(), Duration.ofMinutes(10)));
        response = () -> {
            throw conflict;
        };

        JsonNode result = client.callTool("manage_tags", with(active(), "confirmationCode",
            CODE));

        JsonNode error = JSON.readTree(result.path("content").get(0).path("text").asString())
            .path("error");
        assertThat(error.path("code").asString()).isEqualTo("CONFLICT");
        JsonNode preview = error.path("details").path("preview");
        assertThat(preview.path("action").asString()).isEqualTo("setActiveTag");
        assertThat(preview.path("confirmation").path("code").asString())
            .isEqualTo(conflict.preview().confirmation().orElseThrow().code())
            .isNotEqualTo(CODE);
        @SuppressWarnings("unchecked")
        Map<String, Object> asOutput = Map.of("preview", JSON.treeToValue(preview, Map.class));
        assertThat(errors(asOutput)).as("the preview of the output schema").isNull();
    }

    @Test
    void aPreconditionFailedRefusalHasNoDetails() {
        response = () -> {
            throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "Another change of activeTag:jdoe (OPERATION) on test/node1 is running;"
                    + " nothing was changed", "twice", "Wait").withNode(TEST));
        };

        JsonNode error = JSON.readTree(client.callTool("manage_tags", active())
            .path("content").get(0).path("text").asString()).path("error");

        assertThat(error.path("code").asString()).isEqualTo("PRECONDITION_FAILED");
        assertThat(error.has("details")).isFalse();
    }

    // --- leak check (SC-005) ------------------------------------------------------------------

    @Test
    void noSecretLeavesThroughAPreviewOrAResult() {
        String planted = "password=" + TestSecrets.CONFIGURED_PASSWORD + " "
            + TestSecrets.FREE_TEXT_SECRETS;
        response = () -> new Response.Preview(new ChangePreview(TEST, "manage_tags",
            "setActiveTag", "operation", "active tag of the user jdoe", "active tag " + planted,
            "active tag REL-2", Optional.empty(), List.of("effect "
                + TestSecrets.CONFIGURED_PASSWORD), "none", Optional.empty(),
            List.of("warning " + TestSecrets.CONFIGURED_PASSWORD)));
        JsonNode preview = client.callTool("manage_tags", active());
        assertMatchesOutputSchema("manage_tags", preview);

        response = () -> new Response.Completed(new ChangeResult(TEST, "manage_tags",
            "setActiveTag", "active tag of the user jdoe", ChangeOutcome.FAILED,
            "active tag " + planted, "StartCLI said " + planted, Optional.empty(),
            Optional.of("re-read " + planted), OptionalInt.empty(), Optional.empty(),
            ChangeResult.RestartNeeded.FALSE, AUDIT), List.of());
        JsonNode result = client.callTool("manage_tags", active());
        assertMatchesOutputSchema("manage_tags", result);

        for (JsonNode text : List.of(preview, result)) {
            TestSecrets.assertNoLeak(text.toString());
        }
    }

    // --- helpers ------------------------------------------------------------------------------

    /** The structured content of {@code result}, validated against the output schema. */
    private static JsonNode structured(JsonNode result) {
        assertMatchesOutputSchema("manage_tags", result);
        return result.path("structuredContent");
    }

    private static ChangePreview activePreview() {
        return new ChangePreview(TEST, "manage_tags", "setActiveTag", "operation",
            "active tag of the user jdoe", "active tag REL-1", "active tag REL-2",
            Optional.empty(), List.of("The active tag of jdoe stays REL-2 until it is set again"
                + " or removed."), "none: an active tag is a setting of the user, not an object"
                + " INUBIT exports; the preview shows the current one",
            Optional.of(new ChangePreview.Confirmation(CODE, EXPIRES)), List.of());
    }

    /** {@code setActiveTag} of jdoe to REL-2, a valid call. */
    private static Map<String, Object> active() {
        return call("setActiveTag", Map.of("user", "jdoe", "tag", "REL-2"));
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
            .load("schemas/manage_tags.output.json"), Map.class);
        JsonSchemaValidator.ValidationResponse validation =
            new DefaultJsonSchemaValidator().validate(schema, result);
        return validation.valid() ? null : validation.errorMessage();
    }
}
