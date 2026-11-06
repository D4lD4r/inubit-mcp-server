package de.dadecker.inubit.mcp.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.application.InventoryService;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Feature 013 (T021, FR-001, FR-006): the read-only StartCLI tools through the real launch of the
 * host platform — on Windows {@code startcli.bat} through the command interpreter. The substitute
 * StartCLI writes the recorded export ZIPs of {@code fixtures/v8_1/cli/} and replays the recorded
 * output with CRLF line ends, as the client prints it on Windows. The results equal those of the
 * simulated launch with the unchanged macOS recordings, and no temporary data remains.
 */
@Timeout(120)
class WindowsReadOnlyToolsTest {

    private static WireMockServer dev;
    private static WireMockServer integration;

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
    }

    @Test
    void listInventoryOfTheModulesEqualsTheMacOsResult() throws IOException {
        assertSameResult(ListInventoryTool::new, "list_inventory",
            Map.of("target", "dev/node1", "kind", "MODULE", "limit", 100));
    }

    @Test
    void getInventoryItemWithItsHistoryEqualsTheMacOsResult() throws IOException {
        assertSameResult(GetInventoryItemTool::new, "get_inventory_item",
            Map.of("target", "dev/node1", "kind", "DIAGRAM", "name", "Workflow-0101"));
    }

    private void assertSameResult(Function<InventoryService, ToolHandler> tool, String name,
        Map<String, Object> arguments) throws IOException {
        List<Path> before = exportDirectories();
        JsonNode launched;
        try (InventoryToolFixture fixture =
            InventoryToolFixture.throughTheLaunch(dev, integration, tool)) {
            launched = fixture.client.callTool(name, arguments);
        }
        JsonNode simulated;
        try (InventoryToolFixture fixture = new InventoryToolFixture(dev, integration, tool)) {
            simulated = fixture.client.callTool(name, arguments);
        }

        DiagnosisToolFixture.assertMatchesOutputSchema(name, launched);
        JsonNode entry = launched.path("structuredContent").path("results").path(0);
        assertThat(entry.has("error")).as(launched.toString()).isFalse();
        assertThat(withoutCollectedAt(launched)).isEqualTo(withoutCollectedAt(simulated));
        assertThat(exportDirectories()).as("no temporary data remains").isEqualTo(before);
    }

    /** The result without the collection times, which differ between the two calls. */
    private static JsonNode withoutCollectedAt(JsonNode result) {
        JsonNode copy = result.path("structuredContent").deepCopy();
        copy.path("results").forEach(entry -> ((ObjectNode) entry).remove("collectedAt"));
        return copy;
    }

    /** This process's export directories of the profile {@code acme} below the temp directory. */
    private static List<Path> exportDirectories() throws IOException {
        String prefix = CliResources.EXPORT_PREFIX + "acme-" + ProcessHandle.current().pid() + "-";
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(path -> path.getFileName().toString().startsWith(prefix))
                .sorted().toList();
        }
    }
}
