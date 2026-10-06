package de.dadecker.inubit.mcp.mcp.tools;

import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.assertMatchesOutputSchema;
import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.toolError;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.application.ExportHarness;
import de.dadecker.inubit.mcp.application.ResultLimiter;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * T023: {@code export_artifacts} over MCP (contracts/mcp-tools-delta.md) on a real workspace
 * history with a fake StartCLI.
 */
@Timeout(60)
class ExportArtifactsToolTest {

    private static final String DESCRIPTION = "[acme] Export technical workflows (by diagram"
        + " group) or single modules from one group or node into the local workspace as readable"
        + " files, record the export in the workspace history and list what changed. Only"
        + " technical workflows are exported (no system diagrams or other diagram types). Secrets"
        + " are replaced by placeholders. Read-only for INUBIT.";
    private static final int MAX_ITEMS = 5;

    @TempDir
    java.nio.file.Path root;

    private ExportHarness harness;
    private McpTestClient client;

    @BeforeEach
    void setUp() {
        harness = new ExportHarness(root);
        harness.artifacts.exports.put("GRP-01", ArtifactFixtures.bytes("grp-a.zip"));
        harness.artifacts.exports.put("GRP-02", ArtifactFixtures.bytes("grp-b.zip"));
        harness.artifacts.exports.put("Module-0023", ArtifactFixtures.bytes("module-one.zip"));
        harness.pluginTypes.put("Module-0023", "XSLT Converter");
        TargetResolver targets = new TargetResolver(List.of(ExportHarness.DEV,
            NodeId.parse("dev/node2"), NodeId.parse("qa/node1")));
        client = McpTestClient.start(List.of(new ExportArtifactsTool(harness.service(), targets,
            node -> node.group().value().equals("dev") ? Optional.of("jdoe") : Optional.empty(),
            new ResultLimiter(MAX_ITEMS, ResultLimiter.DEFAULT_MAX_CHARS))));
        client.initialize();
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    private JsonNode call(Map<String, Object> arguments) {
        return client.callTool("export_artifacts", arguments);
    }

    private static JsonNode content(JsonNode result) {
        return result.path("structuredContent");
    }

    @Test
    void theToolIsReadOnlyButNotIdempotent() {
        JsonNode tool = client.listTools().path("tools").get(0);

        assertThat(tool.path("name").asString()).isEqualTo("export_artifacts");
        assertThat(tool.path("description").asString()).isEqualTo(DESCRIPTION);
        JsonNode annotations = tool.path("annotations");
        assertThat(annotations.path("readOnlyHint").asBoolean()).isTrue();
        assertThat(annotations.path("destructiveHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("idempotentHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("openWorldHint").asBoolean()).isTrue();
    }

    @Test
    void aGroupUsesItsFirstNodeAndTheChangesAreBounded() throws IOException {
        JsonNode result = call(Map.of("target", "dev", "diagramGroups", List.of("GRP-01")));

        assertMatchesOutputSchema("export_artifacts", result);
        JsonNode export = content(result);
        assertThat(export.path("node").asString()).isEqualTo("dev/node1");
        assertThat(export.path("owner").asString()).isEqualTo("jdoe");
        assertThat(export.path("workspace").asString()).isEqualTo(root.toString());
        assertThat(export.path("unchanged").asBoolean()).isFalse();
        assertThat(export.has("localChanges")).isFalse();
        String commit = export.path("commit").asString();
        assertThat(harness.git("rev-parse", "--short", "HEAD").strip()).isEqualTo(commit);
        int total = export.path("counts").path("added").asInt()
            + export.path("counts").path("modified").asInt()
            + export.path("counts").path("deleted").asInt();
        assertThat(total).isGreaterThan(MAX_ITEMS);
        assertThat(export.path("changes")).hasSize(MAX_ITEMS);
        assertThat(export.path("truncated").asBoolean()).isTrue();
        assertThat(export.path("fullList").asString())
            .isEqualTo(".reports/export-" + commit + ".txt");
        assertThat(Files.readAllLines(root.resolve(".reports/export-" + commit + ".txt")))
            .hasSize(total).contains("ADDED dev/jdoe/workflows/GRP-01/Workflow-0001.xml");
        assertThat(export.path("warnings").get(0).asString())
            .isEqualTo("Workflow Workflow-0002 is in edit mode by jdoe (CheckoutUser)");
        assertThat(harness.history.status()).as("the report is not versioned").isEmpty();
        assertThat(harness.artifacts.calls).containsExactly("group jdoe GRP-01");
    }

    @Test
    void anUnchangedExportHasNoCommit() {
        call(Map.of("target", "dev/node1", "diagramGroups", List.of("GRP-01")));

        JsonNode result = call(Map.of("target", "dev/node1", "diagramGroups", List.of("GRP-01")));

        assertMatchesOutputSchema("export_artifacts", result);
        assertThat(content(result).path("unchanged").asBoolean()).isTrue();
        assertThat(content(result).has("commit")).isFalse();
        assertThat(content(result).path("changes")).isEmpty();
        assertThat(content(result).path("truncated").asBoolean()).isFalse();
    }

    @Test
    void anExplicitOwnerAndModulesWithoutPluginType() {
        JsonNode result = call(Map.of("target", "dev", "owner", "OWNERS",
            "modules", List.of(Map.of("name", "Module-0023"))));

        assertMatchesOutputSchema("export_artifacts", result);
        assertThat(content(result).path("owner").asString()).isEqualTo("OWNERS");
        assertThat(content(result).path("commit").asString()).isNotBlank();
        assertThat(harness.artifacts.calls).containsExactly("listModules OWNERS",
            "module OWNERS XSLT Converter Module-0023");
    }

    @Test
    void aProductionGroupCanBeExportedWithoutAnyWriteSetting() throws Exception {
        // FR-011: export is read-only and allowed on production groups, like health and logs
        com.github.tomakehurst.wiremock.WireMockServer prod =
            de.dadecker.inubit.mcp.adapter.rest.TestCertificates.httpsWireMock(
                de.dadecker.inubit.mcp.adapter.rest.TestCertificates.get().localhostKeyStore());
        try {
            prod.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(
                com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo(
                    "/ibis/rest/system/info")).willReturn(
                        de.dadecker.inubit.mcp.adapter.rest.RestFixtures.response("system_info",
                            "xml")));
            java.nio.file.Path cliHome = Files.createDirectories(root.resolve(
                ".cli/bin")).getParent();
            Files.writeString(cliHome.resolve("bin/startcli.sh"), "#!/bin/sh\n");
            java.nio.file.Path workspace = Files.createDirectories(root.resolve(".prod-ws"));
            String yaml = """
                profile:
                  name: acme
                workspace: %s
                groups:
                  - name: prod
                    production: true
                    cli:
                      home: %s
                      javaHome: %s
                    inventory:
                      owner: jdoe
                    tls:
                      trustStore: "%s"
                    nodes:
                      - name: node1
                        baseUrl: https://localhost:%d
                        versionLine: V8_1
                """.formatted(workspace, cliHome, System.getProperty("java.home"),
                de.dadecker.inubit.mcp.adapter.rest.TestCertificates.get().trustStore(),
                prod.httpsPort());
            var config = new de.dadecker.inubit.mcp.config.ConfigLoader(Map.of(), root, false)
                .parse(yaml, root.resolve("acme.yaml")).config();
            var scrubber = new de.dadecker.inubit.mcp.infra.SecretScrubber();
            var credentials = new de.dadecker.inubit.mcp.config.CredentialResolver(Map.of(
                "INUBIT_ACME_PROD_USERNAME", "jdoe", "INUBIT_ACME_PROD_PASSWORD",
                "prod-fixture-pw"), scrubber, config.credentialPrefix())
                .resolve(config.nodeIds());
            java.util.regex.Pattern exportFile =
                java.util.regex.Pattern.compile("--exportFile '([^']+)'");
            var startCli = de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher.of(
                "1-OK: Workflow group exported successfully.\n", "", 0).onLaunch(spec -> {
                    String command = spec.command().get(spec.command().indexOf("--execCommand")
                        + 1);
                    java.util.regex.Matcher file = exportFile.matcher(command);
                    if (file.find()) {
                        try {
                            Files.write(java.nio.file.Path.of(file.group(1)),
                                ArtifactFixtures.bytes("grp-a.zip"));
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    }
                });
            try (var wiring = de.dadecker.inubit.mcp.TestWiring.of(config, credentials,
                scrubber, Files::exists, false, startCli, Map.of("PATH", "/usr/bin"));
                McpTestClient production = McpTestClient.start(wiring.toolHandlers(),
                    scrubber)) {
                production.initialize();

                JsonNode result = production.callTool("export_artifacts", Map.of("target",
                    "prod", "diagramGroups", List.of("GRP-01")));

                assertMatchesOutputSchema("export_artifacts", result);
                assertThat(content(result).path("node").asString()).isEqualTo("prod/node1");
                assertThat(content(result).path("unchanged").asBoolean()).isFalse();
                assertThat(workspace.resolve("prod/jdoe/workflows/GRP-01/Workflow-0001.xml"))
                    .isRegularFile();
            }
        } finally {
            prod.stop();
        }
    }

    @Test
    void invalidRequestsAreRefusedBeforeStartCli() {
        assertThat(toolError(call(Map.of("target", "prod", "diagramGroups", List.of("GRP-01"))))
            .path("code").asString()).isEqualTo("TARGET_UNKNOWN");
        assertThat(toolError(call(Map.of("target", "dev"))).path("code").asString())
            .isEqualTo("INVALID_INPUT");
        assertThat(toolError(call(Map.of("target", "dev", "diagramGroups", List.of("GRP-01"),
            "modules", List.of(Map.of("name", "Module-0023"))))).path("code").asString())
            .isEqualTo("INVALID_INPUT");
        assertThat(toolError(call(Map.of("target", "qa", "diagramGroups", List.of("GRP-01"))))
            .path("code").asString()).isEqualTo("NOT_CONFIGURED");
        JsonNode blank = call(Map.of("target", "dev", "diagramGroups", List.of(" ")));
        assertThat(blank.path("isError").asBoolean()).isTrue();
        assertThat(blank.has("structuredContent")).isFalse();
        JsonNode quote = call(Map.of("target", "dev", "diagramGroups", List.of("it's")));
        assertThat(quote.path("isError").asBoolean()).isTrue();

        assertThat(harness.artifacts.calls).isEmpty();
    }
}
