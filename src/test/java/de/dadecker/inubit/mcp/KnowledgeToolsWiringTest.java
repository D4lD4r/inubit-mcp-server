package de.dadecker.inubit.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.infra.ClockProvider;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.testing.FakeCredentialStore;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * 019-T023: the production wiring of the local knowledge (research D-7, D-8, D-15):
 * {@code read_knowledge} always offered, the profile's credentials as the masking source, and the
 * knowledge paragraph in the instructions of the server the wiring builds.
 */
@Timeout(60)
class KnowledgeToolsWiringTest {

    private static final String PASSWORD = "wiring-knowledge-pw";

    @TempDir
    Path temp;

    private ProfileConfig config(boolean recording) {
        String yaml = """
            profile:
              name: acme
            workspace: %s
            knowledge:
              directory: %s
              recording: %s
            groups:
              - name: dev
                nodes:
                  - name: node1
                    baseUrl: https://localhost:1
            """.formatted(temp.resolve("workspace"), temp.resolve("knowledge"), recording);
        return new ConfigLoader(Map.of(), temp, false).parse(yaml, temp.resolve("config.yaml"))
            .config();
    }

    private static FakeCredentialStore store() {
        return TestSecrets.store(Map.of("INUBIT_ACME_DEV_USERNAME", "user",
            "INUBIT_ACME_DEV_PASSWORD", PASSWORD));
    }

    private Wiring wiring(ProfileConfig config, FakeCredentialStore store,
        SecretScrubber scrubber) {
        ProcessLauncher failing = spec -> {
            throw new IOException("StartCLI must not be launched in this test");
        };
        return new Wiring(config, store, scrubber, ClockProvider.system(), path -> false, false,
            failing, Map.of(), () -> {
                throw new AssertionError("no node reads from the Parameter Store");
            }, TestWiring.TEST_CONFIG_LOCATION);
    }

    private void note(String name, String text) throws IOException {
        Path file = temp.resolve("knowledge").resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @Test
    void theNotesAreListedInTheInstructionsAndReadMaskedThroughTheTool() throws IOException {
        note("partners/acme.md", "---\ntitle: ACME\ntopics: [sap]\n---\nlogin with " + PASSWORD);
        SecretScrubber scrubber = new SecretScrubber();
        FakeCredentialStore store = store();
        try (Wiring wiring = wiring(config(true), store, scrubber);
             McpTestClient client = McpTestClient.start(
                 wiring.serverFactory("0.0.0-test", wiring.toolHandlers()))) {
            String instructions = client.initialize().path("instructions").asString();
            JsonNode page = client.callTool("read_knowledge", Map.of("name", "partners/acme"))
                .path("structuredContent");

            assertThat(instructions).contains("\n\nLocal knowledge: this profile has",
                "To record a finding", "Notes (newest first):",
                "\n- \"partners/acme\" — \"ACME\" [topics: \"sap\"]");
            assertThat(store.reads()).isNotEmpty();
            assertThat(page.path("content").asString()).isEqualTo("login with ***");
            assertThat(client.rawOutput()).doesNotContain(PASSWORD);
        }
    }

    @Test
    void withRecordingOffTheParagraphIsReadOnlyAndTheToolStillOffered() throws IOException {
        note("a.md", "x");
        SecretScrubber scrubber = new SecretScrubber();
        try (Wiring wiring = wiring(config(false), store(), scrubber);
             McpTestClient client = McpTestClient.start(
                 wiring.serverFactory("0.0.0-test", wiring.toolHandlers()))) {
            String instructions = client.initialize().path("instructions").asString();

            assertThat(instructions).contains("The local knowledge is read-only for this profile")
                .doesNotContain("To record a finding");
            assertThat(wiring.toolHandlers().stream().map(ToolHandler::name))
                .contains("read_knowledge");
        }
    }

    @Test
    void withoutNotesNoCredentialIsReadAtStart() {
        FakeCredentialStore store = store();
        SecretScrubber scrubber = new SecretScrubber();
        try (Wiring wiring = wiring(config(true), store, scrubber);
             McpTestClient client = McpTestClient.start(
                 wiring.serverFactory("0.0.0-test", wiring.toolHandlers()))) {
            String instructions = client.initialize().path("instructions").asString();

            assertThat(instructions).contains("The local knowledge of this profile is empty.");
            assertThat(store.reads()).isEmpty();
        }
    }
}
