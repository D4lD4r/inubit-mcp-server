package de.dadecker.inubit.mcp.mcp.tools;

import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.assertMatchesOutputSchema;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;

/**
 * T088: {@code get_inventory_item} over MCP with the real adapters against WireMock and a fake
 * StartCLI (contracts/mcp-tools.md §6, quickstart V8).
 */
@Timeout(60)
class GetInventoryItemToolTest {

    private static final String DESCRIPTION = "[acme] Show details of one diagram or module:"
        + " version history (version, check-in user and time, comment, tags), active flag,"
        + " modules used (diagrams), last change. Call it with the id of one group to compare its"
        + " nodes.";
    private static WireMockServer dev;
    private static WireMockServer integration;

    private InventoryToolFixture fixture;

    @BeforeAll
    static void start() {
        dev = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        integration = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
    }

    @AfterAll
    static void stop() {
        dev.stop();
        integration.stop();
    }

    @BeforeEach
    void setUp() {
        dev.resetAll();
        integration.resetAll();
        InventoryToolFixture.stubInventory(dev);
        InventoryToolFixture.stubInventory(integration);
        fixture = new InventoryToolFixture(dev, integration, GetInventoryItemTool::new);
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    private JsonNode call(Map<String, Object> arguments) {
        return fixture.client.callTool("get_inventory_item", arguments);
    }

    private static JsonNode entry(JsonNode result, int index) {
        return result.path("structuredContent").path("results").get(index);
    }

    @Test
    void theToolIsListedReadOnlyIdempotentAndOpenWorldWithTheContractSchema() {
        JsonNode tool = fixture.client.listTools().path("tools").get(0);

        assertThat(tool.path("name").asString()).isEqualTo("get_inventory_item");
        assertThat(tool.path("description").asString()).isEqualTo(DESCRIPTION);
        JsonNode annotations = tool.path("annotations");
        assertThat(annotations.path("readOnlyHint").asBoolean()).isTrue();
        assertThat(annotations.path("destructiveHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("idempotentHint").asBoolean()).isTrue();
        assertThat(annotations.path("openWorldHint").asBoolean()).isTrue();
        JsonNode input = tool.path("inputSchema");
        assertThat(input.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(input.path("required")).extracting(JsonNode::asString)
            .containsExactly("target", "kind", "name");
        assertThat(input.path("properties").propertyNames()).containsExactlyInAnyOrder(
            "target", "kind", "name", "refresh");
        assertThat(input.path("properties").path("kind").path("enum"))
            .extracting(JsonNode::asString).containsExactly("DIAGRAM", "MODULE");
        assertThat(input.path("properties").path("name").path("minLength").asInt())
            .isEqualTo(1);
        assertThat(input.path("properties").path("name").path("maxLength").asInt())
            .isEqualTo(200);
        assertThat(input.path("properties").path("refresh").path("default").asBoolean(true))
            .isFalse();
        JsonNode item = tool.path("outputSchema").path("$defs").path("InventoryDetail")
            .path("properties");
        assertThat(item.propertyNames()).doesNotContain("activeVersion");
    }

    @Test
    void aWorkflowShowsItsVersionsNewestFirstActiveFlagAndModules() {
        // quickstart V8
        JsonNode result = call(Map.of("target", "dev/node1", "kind", "DIAGRAM",
            "name", "Workflow-0101"));

        assertMatchesOutputSchema("get_inventory_item", result);
        JsonNode entry = entry(result, 0);
        assertThat(entry.has("error")).isFalse();
        assertThat(entry.has("collectedAt")).isTrue();
        JsonNode item = entry.path("item");
        assertThat(item.path("name").asString()).isEqualTo("Workflow-0101");
        assertThat(item.path("type").asString()).isEqualTo("technical");
        assertThat(item.path("group").asString()).isEqualTo("GRP-41");
        assertThat(item.path("owner").asString()).isEqualTo("OWNERS");
        assertThat(item.path("active").asBoolean()).isTrue();
        assertThat(item.path("checkinComment").asString()).isEqualTo("[message 107]");
        assertThat(item.path("modules")).hasSize(3);
        assertThat(item.path("modules").get(1).path("nodeId").asString()).isEqualTo("102");
        List<Integer> versions = new ArrayList<>();
        item.path("versions").forEach(version -> versions.add(version.path("version").asInt()));
        assertThat(versions).containsExactly(6, 5, 4, 3, 2, 1);
        JsonNode newest = item.path("versions").get(0);
        assertThat(newest.path("checkinUser").asString()).isEqualTo("user1");
        assertThat(Instant.parse(newest.path("checkinAt").asString()))
            .isEqualTo(Instant.parse("2026-01-23T06:54:27Z"));
        assertThat(newest.path("tags").isArray()).isTrue();
        assertThat(Instant.parse(item.path("lastChange").asString()))
            .isEqualTo(Instant.parse("2026-01-23T06:54:27Z"));
        assertThat(item.has("activeVersion")).isFalse();
        assertThat(item.path("unavailable")).isEmpty();
        assertThat(item.path("truncated").asBoolean(true)).isFalse();
        String command = fixture.launcher.last().spec().command().toString();
        assertThat(command).contains("--exportWorkflowGroup 'GRP-41'")
            .doesNotContain(InventoryToolFixture.PASSWORD);
    }

    @Test
    void anUnknownNameIsNotFoundWithSimilarNamesInsideASuccessfulResult() {
        JsonNode result = call(Map.of("target", "dev/node1", "kind", "DIAGRAM",
            "name", "orkflow-0101"));

        assertMatchesOutputSchema("get_inventory_item", result);
        JsonNode entry = entry(result, 0);
        assertThat(entry.path("error").path("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(entry.path("item").path("similarNames")).isNotEmpty().hasSizeLessThanOrEqualTo(5);
        assertThat(entry.path("item").path("similarNames").get(0).asString())
            .isEqualTo("Workflow-0101");
        assertThat(entry.path("item").has("versions")).isFalse();
        assertThat(fixture.launcher.launchCount()).isZero();
    }

    @Test
    void aStageTargetComparesServersAndReportsTheMissingCliAsUnavailable() {
        JsonNode result = call(Map.of("target", "qa", "kind", "DIAGRAM",
            "name", "Workflow-0101"));

        assertMatchesOutputSchema("get_inventory_item", result);
        JsonNode first = entry(result, 0);
        assertThat(first.path("node").asString()).isEqualTo("qa/node1");
        assertThat(first.path("item").path("active").asBoolean()).isTrue();
        assertThat(first.path("item").has("versions")).isFalse();
        JsonNode unavailable = first.path("item").path("unavailable").get(0);
        assertThat(unavailable.path("part").asString()).isEqualTo("versions");
        assertThat(unavailable.path("reason").asString()).startsWith("CLI_UNAVAILABLE: ");
        assertThat(entry(result, 1).path("node").asString()).isEqualTo("qa/node2");
        assertThat(entry(result, 1).path("error").path("code").asString())
            .isEqualTo("UNREACHABLE");
    }

    @Test
    void aModuleShowsItsIndexEntryWithConnectorFlags() {
        JsonNode result = call(Map.of("target", "dev/node1", "kind", "MODULE",
            "name", "Module-0001"));

        assertMatchesOutputSchema("get_inventory_item", result);
        JsonNode item = entry(result, 0).path("item");
        assertThat(item.path("kind").asString()).isEqualTo("MODULE");
        assertThat(item.path("type").asString()).isEqualTo("AS2 Connector");
        assertThat(item.path("workflows").get(0).asString())
            .as("the connector's own workflow (no workflow node names it here)")
            .isEqualTo("Workflow-0283");
        assertThat(item.path("workflowCount").asInt()).isEqualTo(1);
        assertThat(item.path("usageComplete").asBoolean()).isTrue();
        assertThat(item.path("connector").path("output").asBoolean()).isTrue();
        assertThat(item.path("userComment").asString()).isEqualTo("[message 168]");
        assertThat(Instant.parse(item.path("lastChange").asString()))
            .isEqualTo(Instant.parse("2013-01-18T12:13:25Z"));
        assertThat(item.has("modules")).isFalse();
        assertThat(item.path("unavailable").get(0).path("part").asString())
            .isEqualTo("versions");
    }

    @Test
    void aStoppedHistoryExportIsAPartialResultNotAServerTimeout() {
        // review I4: StartCLI ignores SIGTERM and is killed after the kill grace; the fan-out
        // deadline leaves room for that, so the detail arrives with versions unavailable
        fixture.close();
        fixture = new InventoryToolFixture(dev, integration, GetInventoryItemTool::new,
            FakeProcessLauncher.of("", "", 0).ignoringDestroy(), Duration.ofMillis(300));

        JsonNode result = call(Map.of("target", "dev/node1", "kind", "DIAGRAM",
            "name", "Workflow-0101"));

        assertMatchesOutputSchema("get_inventory_item", result);
        JsonNode entry = entry(result, 0);
        assertThat(entry.has("error")).as(entry.toString()).isFalse();
        assertThat(entry.path("item").path("active").asBoolean()).isTrue();
        assertThat(entry.path("item").has("versions")).isFalse();
        JsonNode unavailable = entry.path("item").path("unavailable").get(0);
        assertThat(unavailable.path("reason").asString()).startsWith("TIMEOUT: ");
        assertThat(unavailable.path("nextStep").asString()).contains("cliExportTimeout");
        assertThat(fixture.launcher.last().destroyedForcibly()).isTrue();
    }
}
