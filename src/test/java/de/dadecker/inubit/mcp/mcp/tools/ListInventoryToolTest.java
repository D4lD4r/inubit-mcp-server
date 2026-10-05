package de.dadecker.inubit.mcp.mcp.tools;

import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.assertMatchesOutputSchema;
import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.toolError;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
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
 * T088: {@code list_inventory} over MCP with the real adapters against WireMock and a fake
 * StartCLI (contracts/mcp-tools.md §5, quickstart V7, V7a).
 */
@Timeout(60)
class ListInventoryToolTest {

    private static final String DESCRIPTION = "[acme] List diagrams (technical workflows, BPDs,"
        + " process maps, …) or modules on one INUBIT node or on all nodes of one group, filtered"
        + " by name, type, or INUBIT diagram/module group.";
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
        fixture = new InventoryToolFixture(dev, integration, ListInventoryTool::new);
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    private JsonNode call(Map<String, Object> arguments) {
        return fixture.client.callTool("list_inventory", arguments);
    }

    private static List<String> names(JsonNode page) {
        List<String> names = new ArrayList<>();
        page.path("items").forEach(item -> names.add(item.path("name").asString()));
        return names;
    }

    @Test
    void theToolIsListedReadOnlyIdempotentAndOpenWorldWithTheContractSchema() {
        JsonNode tool = fixture.client.listTools().path("tools").get(0);

        assertThat(tool.path("name").asString()).isEqualTo("list_inventory");
        assertThat(tool.path("description").asString()).isEqualTo(DESCRIPTION);
        JsonNode annotations = tool.path("annotations");
        assertThat(annotations.path("readOnlyHint").asBoolean()).isTrue();
        assertThat(annotations.path("destructiveHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("idempotentHint").asBoolean()).isTrue();
        assertThat(annotations.path("openWorldHint").asBoolean()).isTrue();
        JsonNode input = tool.path("inputSchema");
        assertThat(input.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(input.path("required")).extracting(JsonNode::asString)
            .containsExactly("target", "kind");
        assertThat(input.path("properties").propertyNames()).containsExactlyInAnyOrder(
            "target", "kind", "nameContains", "type", "group", "offset", "limit", "refresh");
        assertThat(input.path("properties").path("kind").path("enum"))
            .extracting(JsonNode::asString).containsExactly("DIAGRAM", "MODULE");
        assertThat(input.path("properties").path("refresh").path("type").asString())
            .isEqualTo("boolean");
        assertThat(input.path("properties").path("refresh").path("default").asBoolean(true))
            .isFalse();
        assertThat(input.path("properties").path("limit").path("maximum").asInt())
            .isEqualTo(100);
        JsonNode output = tool.path("outputSchema").path("properties").path("results")
            .path("items").path("properties");
        assertThat(output.propertyNames()).contains("node", "collectedAt", "usageComplete",
            "page", "error");
    }

    @Test
    void technicalWorkflowsOfAGroupAreListedSortedByNameWithCollectedAt() {
        // quickstart V7
        JsonNode result = call(Map.of("target", "dev/node1", "kind", "DIAGRAM",
            "type", "technical", "group", "GRP-35", "limit", 100));

        assertMatchesOutputSchema("list_inventory", result);
        JsonNode entry = result.path("structuredContent").path("results").get(0);
        assertThat(entry.path("node").asString()).isEqualTo("dev/node1");
        assertThat(Instant.parse(entry.path("collectedAt").asString())).isBeforeOrEqualTo(
            Instant.now());
        JsonNode page = entry.path("page");
        assertThat(page.path("total").asInt()).isEqualTo(61);
        List<String> names = names(page);
        assertThat(names).hasSize(61).isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);
        page.path("items").forEach(item -> {
            assertThat(item.path("kind").asString()).isEqualTo("DIAGRAM");
            assertThat(item.path("type").asString()).isEqualTo("technical");
            assertThat(item.path("group").asString()).isEqualTo("GRP-35");
            assertThat(item.path("owner").asString()).isEqualTo("OWNERS");
            assertThat(item.has("active")).isFalse();
        });
    }

    @Test
    void modulesComeFromTheCliExportAndARepeatedListingIsServedFromTheCache() {
        // quickstart V7a
        JsonNode first = call(Map.of("target", "dev/node1", "kind", "MODULE",
            "type", "as2 connector", "limit", 100));
        JsonNode second = call(Map.of("target", "dev/node1", "kind", "MODULE",
            "nameContains", "0022"));

        assertMatchesOutputSchema("list_inventory", first);
        assertMatchesOutputSchema("list_inventory", second);
        JsonNode firstEntry = first.path("structuredContent").path("results").get(0);
        JsonNode secondEntry = second.path("structuredContent").path("results").get(0);
        assertThat(firstEntry.path("page").path("total").asInt()).isEqualTo(20);
        assertThat(secondEntry.path("collectedAt")).isEqualTo(firstEntry.path("collectedAt"));
        assertThat(fixture.launcher.launchCount()).as("one module export").isEqualTo(1);
        JsonNode unused = secondEntry.path("page").path("items").get(0);
        assertThat(unused.path("name").asString()).isEqualTo("Module-0022");
        assertThat(unused.path("type").asString()).isEqualTo("AS2 Connector");
        assertThat(unused.path("group").asString()).isEqualTo("AS2 Connector");
        assertThat(unused.path("active").asBoolean()).isTrue();
        assertThat(Instant.parse(unused.path("lastChange").asString()))
            .isEqualTo(Instant.parse("2021-10-06T13:20:50Z"));
        assertThat(unused.path("workflowCount").asInt(-1)).as("no workflow uses it").isZero();
        assertThat(unused.path("workflows")).isEmpty();
        assertThat(firstEntry.path("usageComplete").asBoolean()).isTrue();
        assertThat(dev.findAll(getRequestedFor(urlPathMatching("/ibis/rest/model/modelByName/.+"))))
            .as("every technical workflow is read once for the usage index").hasSize(392);

        JsonNode refreshed = call(Map.of("target", "dev/node1", "kind", "MODULE",
            "refresh", true, "limit", 1));
        assertMatchesOutputSchema("list_inventory", refreshed);
        assertThat(fixture.launcher.launchCount()).isEqualTo(2);
    }

    @Test
    void aModuleUsedOnlyThroughAWorkflowNodeIsListedWithThatWorkflow() {
        // T126 / F1: the module export names no workflow for it; a workflow node does
        dev.stubFor(get(urlPathEqualTo("/ibis/rest/model/modelByName/Workflow-0383"))
            .atPriority(1).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/xml")
                .withBody(InventoryToolFixture.model("Workflow-0383",
                    "<ns4:Node name=\"Module-0022\" type=\"twAS2Connector\""
                        + " id=\"7\"/>"))));

        JsonNode result = call(Map.of("target", "dev/node1", "kind", "MODULE",
            "nameContains", "odule-0022"));

        assertMatchesOutputSchema("list_inventory", result);
        JsonNode entry = result.path("structuredContent").path("results").get(0);
        assertThat(entry.path("usageComplete").asBoolean()).isTrue();
        JsonNode item = entry.path("page").path("items").get(0);
        assertThat(item.path("workflowCount").asInt()).isEqualTo(1);
        assertThat(item.path("workflows").get(0).asString()).isEqualTo("Workflow-0383");
    }

    @Test
    void aPasswordRejectedMidSessionCostsOneFailedLoginWhenTheUsageIndexIsRebuilt() {
        // follow-up N1: the lists are cached and the credentials confirmed; an incomplete index
        // (one workflow fails) is rebuilt on the next call, by then INUBIT rejects the password
        dev.stubFor(get(urlPathEqualTo("/ibis/rest/model/modelByName/Workflow-0383"))
            .atPriority(1).willReturn(aResponse().withStatus(500).withBody("boom")));
        JsonNode first = call(Map.of("target", "dev/node1", "kind", "MODULE", "limit", 1));
        assertThat(first.path("structuredContent").path("results").get(0)
            .path("usageComplete").asBoolean(true)).isFalse();
        dev.resetRequests();
        dev.stubFor(get(urlPathMatching("/ibis/rest/model/modelByName/.+")).atPriority(1)
            .willReturn(aResponse().withStatus(401).withFixedDelay(200)
                .withHeader("Content-Type", "text/html").withBody("<html>401</html>")));

        JsonNode second = call(Map.of("target", "dev/node1", "kind", "MODULE", "limit", 1));

        JsonNode entry = second.path("structuredContent").path("results").get(0);
        assertThat(entry.path("usageComplete").asBoolean(true)).isFalse();
        assertThat(entry.path("page").path("total").asInt()).as("the cached module list")
            .isEqualTo(20);
        assertThat(dev.findAll(getRequestedFor(urlPathMatching("/ibis/rest/.+"))))
            .as("exactly one failed login").hasSize(1);
    }

    @Test
    void aDiagramListCarriesNoUsageFields() {
        JsonNode result = call(Map.of("target", "dev/node1", "kind", "DIAGRAM", "limit", 3));

        JsonNode entry = result.path("structuredContent").path("results").get(0);
        assertThat(entry.has("usageComplete")).isFalse();
        entry.path("page").path("items").forEach(item -> {
            assertThat(item.has("workflows")).isFalse();
            assertThat(item.has("workflowCount")).isFalse();
        });
        assertThat(dev.findAll(getRequestedFor(urlPathMatching("/ibis/rest/model/modelByName/.+"))))
            .isEmpty();
    }

    @Test
    void aStageListsEachServerAndCliLessOrUnreachableServersOnlyGetTheirError() {
        JsonNode diagrams = call(Map.of("target", "qa", "kind", "DIAGRAM", "limit", 2));
        JsonNode modules = call(Map.of("target", "qa", "kind", "MODULE"));

        assertMatchesOutputSchema("list_inventory", diagrams);
        JsonNode results = diagrams.path("structuredContent").path("results");
        assertThat(results).hasSize(2);
        assertThat(results.get(0).path("node").asString()).isEqualTo("qa/node1");
        assertThat(results.get(0).path("page").path("nextOffset").asInt()).isEqualTo(2);
        assertThat(results.get(1).path("node").asString()).isEqualTo("qa/node2");
        assertThat(results.get(1).path("error").path("code").asString())
            .isEqualTo("UNREACHABLE");
        assertThat(results.get(1).has("collectedAt")).isFalse();
        assertThat(results.get(1).has("page")).isFalse();

        assertMatchesOutputSchema("list_inventory", modules);
        JsonNode cliLess = modules.path("structuredContent").path("results").get(0);
        assertThat(cliLess.path("error").path("code").asString()).isEqualTo("CLI_UNAVAILABLE");
        assertThat(fixture.launcher.launchCount()).isZero();
    }

    @Test
    void anUnknownTargetIsAToolErrorAndAnUnknownKindASchemaViolation() {
        JsonNode unknown = call(Map.of("target", "prod", "kind", "DIAGRAM"));
        JsonNode badKind = call(Map.of("target", "dev", "kind", "WORKFLOW"));

        assertThat(toolError(unknown).path("code").asString()).isEqualTo("TARGET_UNKNOWN");
        assertThat(badKind.path("isError").asBoolean()).isTrue();
        assertThat(badKind.has("structuredContent")).isFalse();
        assertThat(badKind.path("content").get(0).path("text").asString())
            .doesNotContain("\"code\"");
        assertThat(dev.getAllServeEvents()).isEmpty();
    }
}
