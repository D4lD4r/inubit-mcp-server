package de.dadecker.inubit.mcp.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.config.ConfirmationMode;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.VersionLine;
import de.dadecker.inubit.mcp.domain.model.E2ePolicy;
import de.dadecker.inubit.mcp.domain.model.ProfileInfo;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;

/** T055, 002-T005, 002-T013: {@code list_nodes} over MCP (contracts/mcp-tools.md §1, FR-003). */
@Timeout(60)
class ListNodesToolTest {

    private static final String DESCRIPTION = "[acme] List the configured INUBIT groups and"
        + " their nodes (ids `<group>/<node>`, e.g. `test/node1`), with production"
        + " classification and whether write actions are enabled. Call this first to learn"
        + " valid `target` and `node` values.";
    private static final ProfileInfo CUSTOM = new ProfileInfo("acme", Optional.of("ACME test"),
        new Terminology("Umgebung", "Umgebungen", "Knoten", "Knoten"), "INUBIT_ACME_SECRETPFX");
    private static final Path CLI_HOME = Path.of("/opt/inubit/client");
    private static final Predicate<Path> EXISTS =
        path -> path.equals(CLI_HOME.resolve("bin").resolve("startcli.sh"));

    private static List<EffectiveNodeConfig> config() {
        return List.of(
            TestNodeConfig.node().id("dev/inubit01")
                .baseUrl("https://inubit01.dev.example.test:8443")
                .write(true, false, ConfirmationMode.CLIENT).cliHome(CLI_HOME)
                .versionLine(VersionLine.AUTO).build(),
            TestNodeConfig.node().id("dev/inubit02")
                .baseUrl("https://inubit02.dev.example.test:8443")
                .development(new EffectiveNodeConfig.Development(true, ConfirmationMode.SERVER,
                    E2ePolicy.FORBIDDEN, Optional.empty())).build(),
            TestNodeConfig.node().id("prod/inubit01")
                .baseUrl("https://inubit01.prod.example.test:8443").production(true)
                .write(true, false, ConfirmationMode.SERVER).cliHome(Path.of("/missing"))
                .build(),
            TestNodeConfig.node().id("prod/inubit02")
                .baseUrl("https://inubit02.prod.example.test:8443").production(true)
                .write(true, true, ConfirmationMode.SERVER).versionLine(VersionLine.V9_X)
                .build());
    }

    private static McpTestClient client() {
        return client(McpTestClient.TEST_PROFILE);
    }

    private static McpTestClient client(ProfileInfo profile) {
        McpTestClient client = McpTestClient.start(profile, List.of(new ListNodesTool(profile,
            config().stream().map(node -> node.summary(EXISTS, false)).toList())));
        client.initialize();
        return client;
    }

    @Test
    void theToolIsListedReadOnlyIdempotentAndClosedWorld() {
        try (McpTestClient client = client()) {
            JsonNode tool = client.listTools().path("tools").get(0);

            assertThat(tool.path("name").asString()).isEqualTo("list_nodes");
            assertThat(tool.path("description").asString()).isEqualTo(DESCRIPTION);
            JsonNode annotations = tool.path("annotations");
            assertThat(annotations.path("readOnlyHint").asBoolean()).isTrue();
            assertThat(annotations.path("destructiveHint").asBoolean()).isFalse();
            assertThat(annotations.path("idempotentHint").asBoolean()).isTrue();
            assertThat(annotations.path("openWorldHint").asBoolean(true)).isFalse();
            assertThat(tool.path("inputSchema").path("additionalProperties").asBoolean(true))
                .isFalse();
        }
    }

    @Test
    void groupsAndNodesAreListedInConfigOrderWithTheEffectiveWriteFlag() {
        try (McpTestClient client = client()) {
            JsonNode result = client.callTool("list_nodes", Map.of());

            assertThat(result.path("isError").asBoolean()).isFalse();
            JsonNode groups = result.path("structuredContent").path("groups");
            assertThat(groups).hasSize(2);
            assertThat(groups.get(0).path("name").asString()).isEqualTo("dev");
            assertThat(groups.get(0).path("production").asBoolean()).isFalse();
            assertThat(groups.get(1).path("name").asString()).isEqualTo("prod");
            assertThat(groups.get(1).path("production").asBoolean()).isTrue();

            JsonNode dev1 = groups.get(0).path("nodes").get(0);
            assertThat(dev1.path("id").asString()).isEqualTo("dev/inubit01");
            assertThat(dev1.path("group").asString()).isEqualTo("dev");
            assertThat(dev1.path("node").asString()).isEqualTo("inubit01");
            assertThat(dev1.path("production").asBoolean()).isFalse();
            assertThat(dev1.path("writeEnabled").asBoolean()).isTrue();
            assertThat(dev1.path("confirmationMode").asString()).isEqualTo("CLIENT");
            assertThat(dev1.path("versionLine").asString()).isEqualTo("AUTO");
            assertThat(dev1.path("cliAvailable").asBoolean()).isTrue();

            JsonNode dev2 = groups.get(0).path("nodes").get(1);
            assertThat(dev2.path("id").asString()).isEqualTo("dev/inubit02");
            assertThat(dev2.path("writeEnabled").asBoolean()).as("write disabled").isFalse();
            // 0.4.2: the development tools (import) do not depend on write.enabled
            assertThat(dev2.path("developmentEnabled").asBoolean()).as("development stage")
                .isTrue();
            assertThat(dev1.path("developmentEnabled").asBoolean()).isFalse();
            assertThat(dev2.path("cliAvailable").asBoolean()).as("no CLI home").isFalse();

            JsonNode prod1 = groups.get(1).path("nodes").get(0);
            assertThat(prod1.path("writeEnabled").asBoolean())
                .as("production without opt-in").isFalse();
            assertThat(prod1.path("cliAvailable").asBoolean()).as("startcli missing").isFalse();

            JsonNode prod2 = groups.get(1).path("nodes").get(1);
            assertThat(prod2.path("writeEnabled").asBoolean()).as("production with opt-in")
                .isTrue();
            assertThat(prod2.path("versionLine").asString()).isEqualTo("V9_X");
            assertThat(prod2.path("production").asBoolean()).isTrue();
        }
    }

    @Test
    void theResultHasNoUrlsUsernamesOrCredentials() {
        try (McpTestClient client = client()) {
            JsonNode result = client.callTool("list_nodes", Map.of());

            String text = result.path("content").get(0).path("text").asString();
            assertThat(text).isNotBlank();
            assertThat(text + result.path("structuredContent"))
                .doesNotContain("https://", "example.test", "baseUrl", "url", "username",
                    "password", "Password", "credential", "trustStore", CLI_HOME.toString());
            JsonNode node = result.path("structuredContent").path("groups").path(0)
                .path("nodes").path(0);
            assertThat(node.propertyNames()).containsExactly("id", "group", "node",
                "production", "writeEnabled", "developmentEnabled", "confirmationMode",
                "versionLine", "cliAvailable");
        }
    }

    @Test
    void theResultNamesTheProfileAndItsTerminology() {
        try (McpTestClient client = client(CUSTOM)) {
            JsonNode result = client.callTool("list_nodes", Map.of()).path("structuredContent");

            assertThat(result.propertyNames()).as("002-T013, contracts/mcp-tools-delta.md")
                .containsExactly("profile", "terminology", "groups");
            assertThat(result.path("profile").propertyNames())
                .containsExactly("name", "description");
            assertThat(result.path("profile").path("name").asString()).isEqualTo("acme");
            assertThat(result.path("profile").path("description").asString())
                .isEqualTo("ACME test");
            JsonNode terminology = result.path("terminology");
            assertThat(terminology.propertyNames()).containsExactly("group", "node");
            assertThat(terminology.path("group").propertyNames())
                .containsExactly("singular", "plural");
            assertThat(terminology.path("group").path("singular").asString())
                .isEqualTo("Umgebung");
            assertThat(terminology.path("group").path("plural").asString())
                .isEqualTo("Umgebungen");
            assertThat(terminology.path("node").path("singular").asString())
                .isEqualTo("Knoten");
            assertThat(terminology.path("node").path("plural").asString()).isEqualTo("Knoten");
        }
    }

    @Test
    void theCredentialPrefixIsNeverPartOfTheResult() {
        try (McpTestClient client = client(CUSTOM)) {
            JsonNode result = client.callTool("list_nodes", Map.of());

            assertThat(result.path("isError").asBoolean()).isFalse();
            assertThat(result.path("content").get(0).path("text").asString()
                + result.path("structuredContent"))
                .as("002 review G6").doesNotContain("SECRETPFX", "INUBIT_ACME", "credential",
                    "Prefix", "prefix");
            assertThat(result.path("structuredContent").path("profile").propertyNames())
                .doesNotContain("credentialPrefix", "terminology");
        }
    }

    @Test
    void aProfileWithoutDescriptionHasNoDescriptionField() {
        try (McpTestClient client = client()) {
            JsonNode profile = client.callTool("list_nodes", Map.of()).path("structuredContent")
                .path("profile");

            assertThat(profile.propertyNames()).containsExactly("name");
            assertThat(profile.path("name").asString()).isEqualTo("acme");
        }
    }

    @Test
    void theDefaultTerminologyIsListedWithoutATerminologySection() {
        try (McpTestClient client = client()) {
            JsonNode terminology = client.callTool("list_nodes", Map.of())
                .path("structuredContent").path("terminology");

            assertThat(terminology.path("group").path("singular").asString()).isEqualTo("group");
            assertThat(terminology.path("group").path("plural").asString()).isEqualTo("groups");
            assertThat(terminology.path("node").path("singular").asString()).isEqualTo("node");
            assertThat(terminology.path("node").path("plural").asString()).isEqualTo("nodes");
        }
    }

    @Test
    void unknownArgumentsAreRejected() {
        try (McpTestClient client = client()) {
            JsonNode result = client.callTool("list_nodes", Map.of("target", "dev"));

            assertThat(result.path("isError").asBoolean()).isTrue();
            assertThat(result.has("structuredContent")).isFalse();
        }
    }
}
