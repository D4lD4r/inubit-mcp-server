package de.dadecker.inubit.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.infra.ClockProvider;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * 019-T034 (FR-001, SC-003, US3 AS1): two profiles in one JVM, {@code acme} and {@code globex},
 * each with its default knowledge directory {@code <home>/.inubit-mcp/<profile>/knowledge}. Each
 * server's instructions and {@code read_knowledge} see only the notes of its own profile, and a
 * note recorded through either profile changes only that profile's directory.
 */
@Timeout(60)
class KnowledgeProfilesTest {

    @TempDir
    Path home;

    private final List<AutoCloseable> closeables = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable closeable : closeables.reversed()) {
            closeable.close();
        }
    }

    private Path knowledge(String profile) {
        return home.resolve(".inubit-mcp").resolve(profile).resolve("knowledge");
    }

    private void note(String profile, String name, String title) throws IOException {
        Path file = knowledge(profile).resolve(name + ".md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "---\ntitle: " + title + "\n---\nOnly for " + profile + ".");
    }

    /** The server of {@code profile}, initialized; its instructions in {@code instructions}. */
    private McpTestClient start(String profile, List<String> instructions) {
        String yaml = """
            profile:
              name: %s
            workspace: %s
            groups:
              - name: dev
                nodes:
                  - name: node1
                    baseUrl: https://localhost:1
            """.formatted(profile, home.resolve("workspace-" + profile));
        ProfileConfig config = new ConfigLoader(Map.of(), home, false)
            .parse(yaml, home.resolve(profile + ".yaml")).config();
        String prefix = "INUBIT_" + profile.toUpperCase() + "_DEV_";
        ProcessLauncher failing = spec -> {
            throw new IOException("StartCLI must not be launched in this test");
        };
        Wiring wiring = new Wiring(config, TestSecrets.store(Map.of(prefix + "USERNAME", "user",
            prefix + "PASSWORD", profile + "-knowledge-pw")), new SecretScrubber(),
            ClockProvider.system(), path -> false, false, failing, Map.of(), () -> {
                throw new AssertionError("no node reads from the Parameter Store");
            }, TestWiring.TEST_CONFIG_LOCATION, home);
        closeables.add(wiring);
        McpTestClient client = McpTestClient.start(
            wiring.serverFactory("0.0.0-test", wiring.toolHandlers()));
        closeables.add(client);
        instructions.add(client.initialize().path("instructions").asString());
        return client;
    }

    private static List<String> listed(McpTestClient client) {
        List<String> names = new ArrayList<>();
        client.callTool("read_knowledge", Map.of()).path("structuredContent").path("notes")
            .forEach(hit -> names.add(hit.path("name").asString()));
        return names;
    }

    private static List<Path> files(Path directory) throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile).map(directory::relativize).sorted()
                .toList();
        }
    }

    @Test
    void eachProfileSeesAndWritesOnlyItsOwnKnowledge() throws IOException {
        note("acme", "partners/acme", "ACME partner");
        note("globex", "partners/globex", "Globex partner");
        List<String> instructions = new ArrayList<>();
        McpTestClient acme = start("acme", instructions);
        McpTestClient globex = start("globex", instructions);
        List<Path> globexBefore = files(knowledge("globex"));

        assertThat(instructions.get(0)).contains("\"partners/acme\" — \"ACME partner\"")
            .doesNotContain("globex", "Globex");
        assertThat(instructions.get(1)).contains("\"partners/globex\" — \"Globex partner\"")
            .doesNotContain("partners/acme", "ACME partner");
        assertThat(listed(acme)).containsExactly("partners/acme");
        assertThat(listed(globex)).containsExactly("partners/globex");
        JsonNode foreign = acme.callTool("read_knowledge", Map.of("name", "partners/globex"));
        assertThat(foreign.path("isError").asBoolean()).isTrue();
        assertThat(foreign.path("content").get(0).path("text").asString())
            .contains("\"NOT_FOUND\"");

        JsonNode recorded = acme.callTool("record_knowledge", Map.of("name", "runbooks/restart",
            "mode", "create", "title", "Restart", "content", "Restart the queue first."));

        assertThat(recorded.path("isError").asBoolean()).isFalse();
        assertThat(knowledge("acme").resolve("runbooks/restart.md")).isRegularFile();
        assertThat(files(knowledge("globex"))).isEqualTo(globexBefore);
        assertThat(listed(acme)).containsExactlyInAnyOrder("partners/acme", "runbooks/restart");
        assertThat(listed(globex)).containsExactly("partners/globex");

        List<Path> acmeBefore = files(knowledge("acme"));
        JsonNode appended = globex.callTool("record_knowledge", Map.of("name", "partners/globex",
            "mode", "append", "content", "Globex sends on Mondays."));

        assertThat(appended.path("isError").asBoolean()).isFalse();
        assertThat(Files.readString(knowledge("globex").resolve("partners/globex.md")))
            .endsWith("Globex sends on Mondays.");
        assertThat(files(knowledge("acme"))).isEqualTo(acmeBefore);
        assertThat(Files.readString(knowledge("acme").resolve("partners/acme.md")))
            .doesNotContain("Globex");
    }
}
