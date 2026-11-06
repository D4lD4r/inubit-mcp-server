package de.dadecker.inubit.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.ConfigLocation;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.testing.GitFreeTempDir;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 015 T012 (research R-16, FR-014): {@code export_archives} is offered exactly when
 * {@code export_artifacts} is (a node has a CLI installation), also when the only group is a
 * production group without the opt-in. T014 (research R-9): the wiring carries the configuration
 * location. T017 (research R-9, R-15): the folder rules get the configuration location, and the
 * production opt-in is {@code archives.productionOptIn}; both refuse before StartCLI.
 */
class ExportArchivesWiringTest {

    @TempDir(factory = GitFreeTempDir.class)
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

    @Test
    void aCallIntoTheConfigurationFilesFolderIsRefusedBeforeStartCli() throws Exception {
        Files.createDirectories(temp.resolve("elsewhere"));

        ToolError error = refusal(cli(), false,
            temp.resolve("elsewhere").resolve("sub").toString(), true);

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).contains("is or lies inside one of the server's directories",
            "the folder of the configuration file in use", temp.resolve("elsewhere").toString());
    }

    @Test
    void aCallIntoTheDefaultConfigurationDirectoryIsRefusedBeforeStartCli() throws Exception {
        Files.createDirectories(temp.resolve("config-default"));

        ToolError error = refusal(cli(), false,
            temp.resolve("config-default").resolve("sub").toString(), true);

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).contains("is or lies inside one of the server's directories",
            "the configuration directory", temp.resolve("config-default").toString());
    }

    @Test
    void aProductionGroupNeedsArchivesProductionOptInNotTheWriteOptIn() {
        ToolError error = refusal("    production: true\n    write:\n      enabled: true\n"
            + "      productionOptIn: true\n" + cli(), false,
            temp.resolve("archives").toString(), true);

        assertThat(error.code()).isEqualTo(ErrorCode.PRODUCTION_PROTECTED);
        assertThat(error.message()).contains("dev/node1", "archives.productionOptIn");
    }

    @Test
    void withArchivesProductionOptInTheNextCheckIsReached() {
        ToolError error = refusal("    production: true\n    archives:\n"
            + "      productionOptIn: true\n" + cli(), false,
            temp.resolve("archives").toString(), false);

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).contains("acknowledgeClearTextSecrets");
    }

    @Test
    void onWindowsAUncPathIsRefusedWithoutAskingTheDriveTypes() {
        ToolError error = refusal(cli(), true, "\\\\server\\share\\archives", true);

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).contains("is a network path (a UNC path)");
    }

    /**
     * Calls {@code export_archives} on {@code dev/node1} through a wiring with the configuration
     * location {@code temp/config-default} and {@code temp/elsewhere/profile.yaml}; StartCLI must
     * not run.
     */
    private ToolError refusal(String groupSettings, boolean windows, String targetFolder,
        boolean acknowledged) {
        ConfigLocation location = new ConfigLocation(temp.resolve("config-default"),
            temp.resolve("elsewhere").resolve("profile.yaml"));
        try (TestWiring wiring = TestWiring.withConfigLocation(config(groupSettings),
            TestSecrets.store(Map.of()), new SecretScrubber(), path -> false, windows,
            spec -> {
                throw new AssertionError("StartCLI is not run");
            }, Map.of(), location)) {
            ToolHandler tool = wiring.toolHandlers().stream()
                .filter(handler -> handler.name().equals("export_archives")).findFirst()
                .orElseThrow();
            ToolErrorException refused = catchThrowableOfType(ToolErrorException.class,
                () -> tool.handle(Map.of("target", "dev/node1",
                    "owner", "OWNERS", "diagramGroup", "GRP-01", "targetFolder", targetFolder,
                    "acknowledgeClearTextSecrets", acknowledged)));
            assertThat(refused).as("refusal").isNotNull();
            return refused.error();
        }
    }
}
