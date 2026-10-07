package de.dadecker.inubit.mcp.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;

/**
 * 002 T043 (research D-13): how the opt-in live tests pick their target. A plain unit test (not
 * tagged {@code live}): a fake home and a fake environment, nothing is contacted.
 */
class LiveTargetTest {

    private static final String PROFILE_YAML = """
        profile:
          name: %1$s
        groups:
          - name: test
            nodes:
              - name: node1
                baseUrl: https://%1$s-test-1.example.test:8443
          - name: prod
            production: true
            nodes:
              - name: node1
                baseUrl: https://%1$s-prod-1.example.test:8443
        """;

    @TempDir
    Path home;

    private final Map<String, String> env = new HashMap<>();

    private LiveTarget resolve() {
        return LiveTarget.resolve(env, home, false);
    }

    private Path profileFile(String name) throws IOException {
        Path file = home.resolve(".config/inubit-mcp/" + name + ".yaml");
        Files.createDirectories(file.getParent());
        return Files.writeString(file, PROFILE_YAML.formatted(name));
    }

    private void credentials(String prefix) {
        env.put(prefix + "_TEST_USERNAME", "live-user");
        env.put(prefix + "_TEST_PASSWORD", "live-test-Pw-4711");
        env.put(prefix + "_PROD_USERNAME", "live-user");
        env.put(prefix + "_PROD_PASSWORD", "live-test-Pw-4711");
    }

    @Test
    void theOldServerVariableIsRefusedWithAHintToTheNodeVariable() {
        env.put("INUBIT_LIVE_SERVER", "test/node1");

        assertThatThrownBy(this::resolve)
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("INUBIT_LIVE_SERVER")
            .hasMessageContaining("INUBIT_LIVE_NODE=<group>/<node>")
            .hasMessageContaining("INUBIT_MCP_PROFILE");
    }

    @Test
    void theOldServerVariableIsRefusedEvenNextToTheNodeVariable() throws IOException {
        profileFile("acme");
        credentials("INUBIT_ACME");
        env.put("INUBIT_MCP_PROFILE", "acme");
        env.put("INUBIT_LIVE_NODE", "test/node1");
        env.put("INUBIT_LIVE_SERVER", "test/node1");

        assertThatThrownBy(this::resolve)
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("INUBIT_LIVE_SERVER")
            .hasMessageContaining("INUBIT_LIVE_NODE");
    }

    @Test
    void theDevelopmentLiveTestAcceptsOnlyADevelopmentNode() throws IOException {
        // feature 004 (T026, research D-23): never a node that is no development stage
        Path file = profileFile("acme");
        credentials("INUBIT_ACME");
        env.put("INUBIT_MCP_PROFILE", "acme");
        env.put("INUBIT_LIVE_DEV_NODE", "test/node1");

        assertThatThrownBy(() -> LiveTarget.resolveDevelopment(env, home, false))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("test/node1 is not a development node");

        Files.writeString(file, Files.readString(file).replace("  - name: test\n",
            "  - name: test\n    development:\n      enabled: true\n"));
        assertThat(LiveTarget.resolveDevelopment(env, home, false).node())
            .isEqualTo(NodeId.parse("test/node1"));
    }

    @Test
    void withoutTheDevelopmentNodeVariableTheDevelopmentLiveTestIsSkipped() {
        env.put("INUBIT_LIVE_NODE", "test/node1");

        assertThatThrownBy(() -> LiveTarget.resolveDevelopment(env, home, false))
            .isInstanceOf(TestAbortedException.class)
            .hasMessageContaining("INUBIT_LIVE_DEV_NODE");
    }

    @Test
    void withoutTheNodeVariableTheLiveTestIsSkipped() {
        assertThatThrownBy(this::resolve)
            .isInstanceOf(TestAbortedException.class)
            .hasMessageContaining("INUBIT_LIVE_NODE");
    }

    @Test
    void aBlankNodeVariableSkipsTheLiveTest() {
        env.put("INUBIT_LIVE_NODE", "  ");

        assertThatThrownBy(this::resolve).isInstanceOf(TestAbortedException.class);
    }

    @Test
    void theNodeVariableIsParsedAndTheProfileVariableSelectsTheConfiguration() throws IOException {
        profileFile("acme");
        Path globex = profileFile("globex");
        credentials("INUBIT_GLOBEX");
        env.put("INUBIT_MCP_PROFILE", "globex");
        env.put("INUBIT_LIVE_NODE", " test/node1 ");

        LiveTarget live = resolve();

        assertThat(live.node()).isEqualTo(NodeId.parse("test/node1"));
        assertThat(live.loaded().source()).isEqualTo(globex);
        assertThat(live.loaded().config().profile().name()).isEqualTo("globex");
        assertThat(live.credentials().credentials(NodeId.parse("test/node1")).username())
            .hasValueSatisfying(username -> assertThat(username.value()).isEqualTo("live-user"));
    }

    @Test
    void resolvingTheTargetCreatesNoWorkspaceOfTheProfile() throws IOException {
        // review M11: live tests must not create the person's real workspace as a side effect
        profileFile("globex");
        credentials("INUBIT_GLOBEX");
        env.put("INUBIT_MCP_PROFILE", "globex");
        env.put("INUBIT_LIVE_NODE", "test/node1");

        resolve();

        assertThat(home.resolve(".inubit-mcp/globex/workspace")).doesNotExist();
    }

    @Test
    void theConfigVariableComesBeforeTheProfileVariable() throws IOException {
        Path acme = profileFile("acme");
        profileFile("globex");
        credentials("INUBIT_ACME");
        env.put("INUBIT_MCP_CONFIG", acme.toString());
        env.put("INUBIT_MCP_PROFILE", "globex");
        env.put("INUBIT_LIVE_NODE", "test/node1");

        assertThat(resolve().loaded().source()).isEqualTo(acme);
    }

    @Test
    void withoutLocationVariablesTheDefaultFileIsUsed() throws IOException {
        Path file = home.resolve(".config/inubit-mcp/config.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, PROFILE_YAML.formatted("acme"));
        credentials("INUBIT_ACME");
        env.put("INUBIT_LIVE_NODE", "test/node1");

        assertThat(resolve().loaded().source()).isEqualTo(file);
    }

    @Test
    void aProductionNodeIsRefusedBeforeAnythingIsContacted() throws IOException {
        profileFile("acme");
        credentials("INUBIT_ACME");
        env.put("INUBIT_MCP_PROFILE", "acme");
        env.put("INUBIT_LIVE_NODE", "prod/node1");

        assertThatThrownBy(this::resolve)
            .isInstanceOf(AssertionError.class)
            .hasMessage("Refusing to run live tests against prod/node1: its group is"
                + " production: true (Constitution III)");
    }

    @Test
    void anUnknownNodeIsReported() throws IOException {
        profileFile("acme");
        credentials("INUBIT_ACME");
        env.put("INUBIT_MCP_PROFILE", "acme");
        env.put("INUBIT_LIVE_NODE", "qa/node1");

        assertThatThrownBy(this::resolve)
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("qa/node1 is not configured");
    }

    @Test
    void aMalformedNodeIdIsReportedWithTheVariableName() {
        env.put("INUBIT_LIVE_NODE", "node1");

        assertThatThrownBy(this::resolve)
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("INUBIT_LIVE_NODE")
            .hasMessageContaining("<group>/<node>");
    }

    // --- feature 005 (T030): the deployment live test ------------------------------------------

    private static final String CHAIN_YAML = """
        profile:
          name: acme
        defaults:
          cliHome: %1$s
        groups:
          - name: dev
            development: { enabled: true }
            nodes: [ { name: node1, baseUrl: "https://acme-dev-1.example.test:8443" } ]
          - name: int
            deploy: { from: dev }
            nodes: [ { name: node1, baseUrl: "https://acme-int-1.example.test:8443" } ]
          - name: pkg
            deploy: { from: int, mode: PACKAGE_ONLY }
            nodes: [ { name: node1, baseUrl: "https://acme-pkg-1.example.test:8443" } ]
          - name: prod
            production: true
            deploy: { from: int, mode: PACKAGE_ONLY }
            nodes: [ { name: node1, baseUrl: "https://acme-prod-1.example.test:8443" } ]
        """;

    private void chainProfile() throws IOException {
        Path client = home.resolve("client");
        Files.createDirectories(client.resolve("bin"));
        Files.writeString(client.resolve("bin/startcli.sh"), "#!/bin/sh\n");
        Path file = home.resolve(".config/inubit-mcp/acme.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, CHAIN_YAML.formatted(client));
        for (String group : new String[] {"DEV", "INT", "PKG", "PROD"}) {
            env.put("INUBIT_ACME_" + group + "_USERNAME", "live-user");
            env.put("INUBIT_ACME_" + group + "_PASSWORD", "live-test-Pw-4711");
        }
        env.put("INUBIT_MCP_PROFILE", "acme");
    }

    @Test
    void withoutTheDeployTargetVariableTheDeploymentLiveTestIsSkipped() {
        assertThatThrownBy(() -> LiveTarget.resolveDeployment(env, home, false))
            .isInstanceOf(TestAbortedException.class)
            .hasMessageContaining("INUBIT_LIVE_DEPLOY_TARGET");
    }

    @Test
    void theDeploymentLiveTestTakesOnlyAChainedNonProductionExecuteGroup() throws IOException {
        chainProfile();
        Map<String, String> refused = Map.of("prod", "production", "pkg", "package-only",
            "dev", "receives no deployments", "int/node1", "the id of one group");
        for (Map.Entry<String, String> target : refused.entrySet()) {
            env.put("INUBIT_LIVE_DEPLOY_TARGET", target.getKey());

            assertThatThrownBy(() -> LiveTarget.resolveDeployment(env, home, false))
                .as(target.getKey()).isInstanceOf(AssertionError.class)
                .hasMessageContaining(target.getValue());
        }
        env.put("INUBIT_LIVE_DEPLOY_TARGET", "int");

        assertThat(LiveTarget.resolveDeployment(env, home, false).node())
            .isEqualTo(NodeId.parse("int/node1"));
    }
}
