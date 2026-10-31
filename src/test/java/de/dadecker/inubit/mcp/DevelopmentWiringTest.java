package de.dadecker.inubit.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.testing.TestSecrets;
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

    private static final String NODE1 = "- name: node1\n  baseUrl: https://localhost:1";

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
        return toolNames(development, production, NODE1);
    }

    /** {@code devNodes}: the entries of the dev group's {@code nodes} list. */
    private List<String> toolNames(String development, String production, String devNodes) {
        String yaml = """
            profile:
              name: acme
            auditDirectory: %s
            workspace: %s
            groups:
              - name: dev
            %s
                nodes:
            %s
              - name: prod
                production: true
            %s
                nodes:
                  - name: inubit01
                    baseUrl: https://localhost:2
            """.formatted(temp.resolve("audit"), temp.resolve("workspace"),
            development.indent(4).stripTrailing(), devNodes.indent(6).stripTrailing(),
            production.indent(4).stripTrailing());
        ProfileConfig config = new ConfigLoader(Map.of(), temp, false)
            .parse(yaml, temp.resolve("config.yaml")).config();
        SecretScrubber scrubber = new SecretScrubber();
        TestWiring wiring = TestWiring.of(config, TestSecrets.store(Map.of(
            "INUBIT_ACME_DEV_USERNAME", "jdoe", "INUBIT_ACME_DEV_PASSWORD", "dev-wiring-pw")),
            scrubber, path -> false, false);
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

    @Test
    void runE2eTestIsOfferedOnANonProductionTargetThatAllowsIt() {
        // feature 005 (T027): dev receives deployments here (from prod), without development
        String target = "deploy:\n  from: prod\ne2e:\n  soap:\n"
            + "    baseUrl: https://inubit-dev.example.test:8443\n";
        assertThat(toolNames(target + "e2eTests: CONFIRM")).contains("run_e2e_test",
            "deploy_release").doesNotContain("import_artifacts");
        assertThat(toolNames(target + "e2eTests: FORBIDDEN")).doesNotContain("run_e2e_test");
    }

    // --- feature 009 (T015, FR-007, research D-12): pinned versions without the capability ---

    private static final String DEVELOPMENT = "development:\n  enabled: true\ne2eTests: CONFIRM\n"
        + "e2e:\n  soap:\n    baseUrl: https://inubit-dev.example.test:8443";

    private static String devNode(String name, String versionLine, int port) {
        return "- name: " + name + "\n  versionLine: " + versionLine
            + "\n  baseUrl: https://localhost:" + port;
    }

    @Test
    void developmentNodesPinnedToV9xOfferNoDevelopmentTools() {
        assertThat(toolNames(DEVELOPMENT, "", devNode("node1", "V9_X", 1))).doesNotContain(
            "import_artifacts", "restore_backup", "set_active", "tag_artifacts", "run_e2e_test");
    }

    @Test
    void oneMoreDevelopmentNodeOn81OffersTheDevelopmentTools() {
        assertThat(toolNames(DEVELOPMENT, "", devNode("node1", "V9_X", 1) + "\n"
            + devNode("node2", "V8_1", 3))).contains("import_artifacts", "restore_backup",
            "set_active", "tag_artifacts", "run_e2e_test");
    }

    @Test
    void aConcretePinIsJudgedByTheMatrixToo() {
        assertThat(toolNames(DEVELOPMENT, "", devNode("node1", "\"9.0.7\"", 1)))
            .doesNotContain("import_artifacts", "run_e2e_test");
        assertThat(toolNames(DEVELOPMENT, "", devNode("node1", "\"8.1.17\"", 1)))
            .contains("import_artifacts", "run_e2e_test");
    }

    @Test
    void executeTargetsPinnedToV9xOfferNeitherDeployReleaseNorRestoreBackup() {
        String target = "deploy:\n  from: dev\nwrite:\n  enabled: true\n"
            + "  productionOptIn: true";
        assertThat(toolNames("", target + "\nversionLine: V9_X")).doesNotContain(
            "deploy_release", "restore_backup");
        assertThat(toolNames("", target + "\nversionLine: V8_1")).contains("deploy_release",
            "restore_backup");
    }
}
