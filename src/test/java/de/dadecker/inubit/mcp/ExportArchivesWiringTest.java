package de.dadecker.inubit.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.ConfigLocation;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 015 T012 (research R-16, FR-014): {@code export_archives} is offered exactly when
 * {@code export_artifacts} is (a node has a CLI installation), also when the only group is a
 * production group without the opt-in. T014 (research R-9): the wiring carries the configuration
 * location.
 */
class ExportArchivesWiringTest {

    @TempDir
    Path temp;

    private ProfileConfig config(String groupSettings) {
        String yaml = """
            profile:
              name: acme
            workspace: %s
            groups:
              - name: dev
            %s
                nodes:
                  - name: node1
                    baseUrl: https://localhost:1
            """.formatted(temp.resolve("workspace"), groupSettings);
        return new ConfigLoader(Map.of(), temp, false)
            .parse(yaml, temp.resolve("config.yaml")).config();
    }

    private List<String> toolNames(String groupSettings) {
        try (TestWiring wiring = TestWiring.of(config(groupSettings), TestSecrets.store(Map.of()),
            new SecretScrubber(), path -> false, false)) {
            return wiring.toolHandlers().stream().map(ToolHandler::name).toList();
        }
    }

    private String cli() {
        return "    cli:\n      home: " + temp.resolve("startcli");
    }

    @Test
    void withACliHomeTheArchiveToolIsOfferedNextToTheExportTool() {
        List<String> names = toolNames(cli());

        assertThat(names).contains("export_artifacts", "export_archives");
        assertThat(names.indexOf("export_archives"))
            .isEqualTo(names.indexOf("export_artifacts") + 1);
    }

    @Test
    void withoutACliHomeItIsNot() {
        assertThat(toolNames("")).doesNotContain("export_archives", "export_artifacts");
    }

    @Test
    void aProductionGroupWithoutTheOptInDoesNotSuppressIt() {
        assertThat(toolNames("    production: true\n" + cli())).contains("export_archives");
    }

    @Test
    void theWiringCarriesTheGivenConfigurationLocation() {
        ConfigLocation location = new ConfigLocation(temp.resolve("config-default"),
            temp.resolve("elsewhere/profile.yaml"));

        try (TestWiring wiring = TestWiring.withConfigLocation(config(cli()),
            TestSecrets.store(Map.of()), new SecretScrubber(), path -> false, false,
            spec -> {
                throw new AssertionError("StartCLI is not run");
            }, Map.of(), location)) {
            assertThat(wiring.configLocation()).isEqualTo(location);
        }
    }

    @Test
    void theExistingFactoriesCarryTheTestLocationBelowTheTemporaryDirectory() {
        try (TestWiring wiring = TestWiring.of(config(cli()), TestSecrets.store(Map.of()),
            new SecretScrubber(), path -> false, false)) {
            assertThat(wiring.configLocation()).isEqualTo(TestWiring.TEST_CONFIG_LOCATION);
        }
        Path tmpdir = Path.of(System.getProperty("java.io.tmpdir"));
        assertThat(TestWiring.TEST_CONFIG_LOCATION).isEqualTo(new ConfigLocation(
            tmpdir.resolve("inubit-mcp-test-config"),
            tmpdir.resolve("inubit-mcp-test-config/config.yaml")));
        assertThat(TestWiring.TEST_CONFIG_LOCATION.roots())
            .containsExactly(tmpdir.resolve("inubit-mcp-test-config"));
    }
}
