package de.dadecker.inubit.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.CredentialResolver;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T018 (feature 004, FR-001, US6 AS 1): the development tools are offered only if at least one
 * node is a development stage.
 */
class DevelopmentWiringTest {

    @TempDir
    Path temp;

    private final List<AutoCloseable> closeables = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable closeable : closeables) {
            closeable.close();
        }
    }

    private List<String> toolNames(String development) {
        return toolNames(development, "");
    }

    private List<String> toolNames(String development, String production) {
        String yaml = """
            profile:
              name: acme
            auditDirectory: %s
            workspace: %s
            groups:
              - name: dev
            %s
                nodes:
                  - name: node1
                    baseUrl: https://localhost:1
              - name: prod
                production: true
            %s
                nodes:
                  - name: inubit01
                    baseUrl: https://localhost:2
            """.formatted(temp.resolve("audit"), temp.resolve("workspace"),
            development.indent(4).stripTrailing(), production.indent(4).stripTrailing());
        ProfileConfig config = new ConfigLoader(Map.of(), temp, false)
            .parse(yaml, temp.resolve("config.yaml")).config();
        SecretScrubber scrubber = new SecretScrubber();
        TestWiring wiring = TestWiring.of(config, new CredentialResolver(Map.of(
            "INUBIT_ACME_DEV_USERNAME", "jdoe", "INUBIT_ACME_DEV_PASSWORD", "dev-wiring-pw"),
            scrubber, config.credentialPrefix()).resolve(config.nodeIds()), scrubber,
            path -> false, false);
        closeables.add(wiring);
        McpTestClient client = McpTestClient.start(wiring.toolHandlers(), scrubber);
        closeables.add(client);
        client.initialize();
        List<String> names = new ArrayList<>();
        client.listTools().path("tools").forEach(tool -> names.add(tool.path("name").asString()));
        return names;
    }

    @Test
    void withoutADevelopmentNodeNoDevelopmentToolIsOffered() {
        assertThat(toolNames("")).doesNotContain("import_artifacts", "restore_backup",
            "set_active", "tag_artifacts");
        assertThat(toolNames("development:\n  enabled: false")).doesNotContain(
            "import_artifacts", "restore_backup", "set_active", "tag_artifacts", "run_e2e_test");
    }

    @Test
    void theSoapTestIsOfferedOnlyWhereANodeAllowsIt() {
        assertThat(toolNames("development:\n  enabled: true")).doesNotContain("run_e2e_test");
        assertThat(toolNames("development:\n  enabled: true\ne2eTests: CONFIRM\ne2e:\n"
            + "  soap:\n    baseUrl: https://inubit-dev.example.test:8443"))
            .contains("run_e2e_test", "import_artifacts");
        assertThat(toolNames("e2eTests: CONFIRM\ne2e:\n  soap:\n"
            + "    baseUrl: https://inubit-dev.example.test:8443"))
            .doesNotContain("run_e2e_test");
    }

    @Test
    void aDevelopmentNodeOffersTheDevelopmentTools() {
        assertThat(toolNames("development:\n  enabled: true")).contains("import_artifacts",
            "restore_backup", "set_active", "tag_artifacts").doesNotContain("restart_process");
    }

    @Test
    void deployReleaseIsOfferedIffAGroupReceivesDeployments() {
        // feature 005 (T024, FR-006)
        assertThat(toolNames("development:\n  enabled: true")).doesNotContain("deploy_release");
        assertThat(toolNames("", "deploy:\n  from: dev\n  mode: PACKAGE_ONLY"))
            .contains("deploy_release").doesNotContain("import_artifacts");
    }

    @Test
    void restoreBackupIsOfferedForDeploymentBackupsOfAnExecuteTarget() {
        // feature 005 (T026)
        assertThat(toolNames("", "deploy:\n  from: dev\nwrite:\n  enabled: true\n"
            + "  productionOptIn: true")).contains("deploy_release", "restore_backup")
            .doesNotContain("import_artifacts", "set_active", "tag_artifacts");
        assertThat(toolNames("", "deploy:\n  from: dev\n  mode: PACKAGE_ONLY"))
            .doesNotContain("restore_backup");
    }
}
