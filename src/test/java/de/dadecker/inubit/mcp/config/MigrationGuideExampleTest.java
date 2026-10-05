package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * 002-T036 (US3, SC-005): the before/after example of {@code docs/migration-001-to-002.md}.
 *
 * <ul>
 *   <li>{@code config/migration-before.yaml} and {@code config/migration-after.yaml} are the
 *       guide's two YAML blocks; the guide must contain each file verbatim, so they stay in sync.
 *   <li>The "before" file (format of feature 001) is refused with the migration guidance.
 *   <li>The "after" file passes the configuration check with the credential variables of feature
 *       001 and yields the same nodes; every node resolves the same variable names as feature 001
 *       did (computed here with the 001 rule from the "before" file).
 * </ul>
 */
class MigrationGuideExampleTest {

    private static final Path GUIDE = Path.of("docs/migration-001-to-002.md");
    private static final Path HOME = Path.of("/home/test");

    /** The variables of the 001 installation, as exported in the user's shell. */
    private static final Map<String, String> ENV_001 = Map.of(
        "INUBIT_DEV_USERNAME", "jdoe",
        "INUBIT_DEV_PASSWORD", "dev-Secret-1",
        "INUBIT_QA_USERNAME", "jdoe",
        "INUBIT_QA_PASSWORD", "qa-Secret-1",
        "INUBIT_QA_NODE2_PASSWORD", "qa-node2-Secret-1");

    private static Path resource(String name) {
        try {
            return Path.of(MigrationGuideExampleTest.class.getResource("/config/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ConfigLoader loader() {
        return new ConfigLoader(ENV_001, HOME, false);
    }

    @Test
    void theGuideContainsTheBeforeAndAfterExampleVerbatim() throws IOException {
        assertThat(GUIDE).exists();
        String guide = Files.readString(GUIDE, StandardCharsets.UTF_8);

        assertThat(guide)
            .contains("```yaml\n" + Files.readString(resource("migration-before.yaml")) + "```")
            .contains("```yaml\n" + Files.readString(resource("migration-after.yaml")) + "```");
    }

    @Test
    void theBeforeExampleIsRefusedWithTheMigrationGuidance() {
        assertThatThrownBy(() -> loader().loadFile(resource("migration-before.yaml")))
            .isInstanceOf(ConfigException.class)
            .hasMessageContaining("stages (top level): rename to groups")
            .hasMessageContaining("stages[0].servers, stages[1].servers")
            .hasMessageContaining("rename to nodes")
            .hasMessageContaining("profile (missing)")
            .hasMessageContaining("credentials.envPrefix: INUBIT")
            .hasMessageContaining("~/.inubit-mcp/audit")
            .hasMessageContaining("docs/migration-001-to-002.md")
            .hasMessageNotContaining("example.test")
            .hasMessageNotContaining("INTEGRATION");
    }

    @Test
    void theGuideShowsTheRefusalOfTheBeforeExampleVerbatim() throws IOException {
        String shownPath = "/Users/jdoe/.config/inubit-mcp/config.yaml";
        Path source = Path.of(shownPath);
        Throwable refusal = catchThrowable(() -> loader().parse(
            Files.readString(resource("migration-before.yaml")), source));

        assertThat(refusal).isInstanceOf(ConfigException.class);
        assertThat(Files.readString(GUIDE, StandardCharsets.UTF_8)).contains(
            "```text\n" + refusal.getMessage().replace(source.toString(), shownPath) + "\n```");
    }

    @Test
    void theAfterExamplePassesTheCheckWithTheSameNodesAndVariableNames() throws IOException {
        Map<NodeId, String[]> expected = variablesOf001(resource("migration-before.yaml"));

        LoadedConfig loaded = loader().loadFile(resource("migration-after.yaml"));
        ProfileConfig config = loaded.config();
        CredentialResolution credentials = new CredentialResolver(ENV_001, new SecretScrubber(),
            config.terminology().effectiveOrDefault(), config.credentialPrefix())
            .resolve(config.nodeIds());
        ValidationReport report = new ConfigValidator(path -> true, ENV_001, false,
            Path.of("/var/tmp")).validate(loaded, credentials);

        assertThat(report.errors()).isEmpty();
        assertThat(report.warnings()).isEmpty();
        assertThat(config.nodeIds()).containsExactlyElementsOf(expected.keySet());
        expected.forEach((node, variables) -> {
            NodeCredentials resolved = credentials.credentials(node);
            assertThat(resolved.username()).as(node + " username")
                .hasValueSatisfying(value -> assertThat(value.sourceVariable()).isEqualTo(variables[0]));
            assertThat(resolved.password()).as(node + " password")
                .hasValueSatisfying(value -> assertThat(value.sourceVariable()).isEqualTo(variables[1]));
        });
        assertThat(config.credentialPrefix()).isEqualTo("INUBIT");
        assertThat(config.auditDirectory())
            .isEqualTo(HOME.resolve(".inubit-mcp").resolve("acme").resolve("audit"));
    }

    @Test
    void theCheckSummaryShowsTheVariableSchemeOf001AndTheOldWording() {
        LoadedConfig loaded = loader().loadFile(resource("migration-after.yaml"));
        ProfileConfig config = loaded.config();
        CredentialResolution credentials = new CredentialResolver(ENV_001, new SecretScrubber(),
            config.terminology().effectiveOrDefault(), config.credentialPrefix())
            .resolve(config.nodeIds());
        ValidationReport report = new ConfigValidator(path -> true, ENV_001, false,
            Path.of("/var/tmp")).validate(loaded, credentials);

        String summary = new ConfigSummary(path -> true, false).render(loaded, credentials, report);

        assertThat(summary)
            .contains("Profile: acme")
            .contains("Terminology: stage/stages, server/servers")
            .contains("Credential variables: INUBIT_<STAGE>[_<SERVER>]_USERNAME / _PASSWORD")
            .contains("Server qa/node2:", "password ← INUBIT_QA_NODE2_PASSWORD")
            .contains("Result: OK")
            .doesNotContain("Secret-1");
    }

    @Test
    void theGuideShowsTheCheckSummaryOfTheAfterExampleVerbatim() throws IOException {
        // rendered as the guide shows it: home /Users/jdoe, file ~/.config/inubit-mcp/acme.yaml
        Path home = Path.of("/Users/jdoe");
        Path source = home.resolve(".config").resolve("inubit-mcp").resolve("acme.yaml");
        LoadedConfig loaded = new ConfigLoader(ENV_001, home, false)
            .parse(Files.readString(resource("migration-after.yaml")), source);
        ProfileConfig config = loaded.config();
        CredentialResolution credentials = new CredentialResolver(ENV_001, new SecretScrubber(),
            config.terminology().effectiveOrDefault(), config.credentialPrefix())
            .resolve(config.nodeIds());
        ValidationReport report = new ConfigValidator(path -> true, ENV_001, false,
            Path.of("/var/tmp")).validate(loaded, credentials);

        String summary = new ConfigSummary(path -> true, false).render(loaded, credentials, report);

        assertThat(summary).startsWith("Configuration: /Users/jdoe/.config/inubit-mcp/acme.yaml\n")
            .contains("Audit directory: /Users/jdoe/.inubit-mcp/acme/audit\n");
        assertThat(Files.readString(GUIDE, StandardCharsets.UTF_8))
            .contains("```text\n" + summary + (summary.endsWith("\n") ? "" : "\n") + "```");
    }

    /**
     * Node ids and (username, password) variable names of the "before" file under the rule of
     * feature 001: {@code INUBIT_<STAGE>_<SERVER>_*} if set, else {@code INUBIT_<STAGE>_*}.
     */
    private static Map<NodeId, String[]> variablesOf001(Path before) throws IOException {
        JsonNode root = YAMLMapper.builder().build().readTree(Files.readString(before));
        Map<NodeId, String[]> nodes = new LinkedHashMap<>();
        for (JsonNode stage : root.get("stages")) {
            String stageName = stage.get("name").asString();
            for (JsonNode server : stage.get("servers")) {
                String serverName = server.get("name").asString();
                List<String> variables = new ArrayList<>();
                for (String kind : List.of("USERNAME", "PASSWORD")) {
                    String specific = "INUBIT_" + normalize(stageName) + "_"
                        + normalize(serverName) + "_" + kind;
                    variables.add(ENV_001.containsKey(specific) ? specific
                        : "INUBIT_" + normalize(stageName) + "_" + kind);
                }
                nodes.put(NodeId.parse(stageName + "/" + serverName),
                    variables.toArray(String[]::new));
            }
        }
        return nodes;
    }

    private static String normalize(String name) {
        return name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }
}
