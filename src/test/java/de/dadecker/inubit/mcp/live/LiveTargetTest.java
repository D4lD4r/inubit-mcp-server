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
}
