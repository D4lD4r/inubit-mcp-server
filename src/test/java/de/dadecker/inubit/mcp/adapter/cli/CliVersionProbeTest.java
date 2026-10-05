package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliVersionProbe.CliVersion;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** T030: {@code startcli.sh -v} without server or credentials (spec edge case, research R-6). */
@Timeout(30)
class CliVersionProbeTest {

    private static final NodeId ID = NodeId.parse("dev/node1");

    @TempDir
    Path cliHome;

    private Path script;

    @BeforeEach
    void installFakeCli() throws IOException {
        script = Files.createDirectories(cliHome.resolve("bin")).resolve("startcli.sh");
        Files.writeString(script, "#!/bin/sh\n");
    }

    private EffectiveNodeConfig server() {
        return TestNodeConfig.node().cliHome(cliHome).cliJavaHome(Path.of("/opt/jdk-17"))
            .trustStore(Path.of("/etc/trust.p12")).disableHostnameVerification("AB".repeat(32))
            .build();
    }

    private static CliVersionProbe probe(FakeProcessLauncher launcher) {
        return new CliVersionProbe(new CliRunner(launcher,
            Map.of("PATH", "/usr/bin", "INUBIT_DEV_PASSWORD", "never-passed"), false,
                new CliResources("acme")));
    }

    @Test
    void parsesTheCliVersionAndTheSupportedServerRange() {
        CliVersion version = probe(FakeProcessLauncher.replaying("version")).probe(server());

        assertThat(version.version()).isEqualTo("8.1.17");
        assertThat(version.supportedFrom()).contains("4.0.1");
        assertThat(version.supportedTo()).contains("8.1.17");
    }

    @Test
    void runsOnlyDashVWithoutCredentialsServerOrStdin() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("version");

        probe(launcher).probe(server());

        assertThat(launcher.last().spec().command()).containsExactly(script.toString(), "-v");
        assertThat(launcher.last().stdinBytes()).isEmpty();
        assertThat(launcher.last().stdinClosed()).isTrue();
        assertThat(launcher.last().spec().environment())
            .containsEntry("JAVA_HOME", "/opt/jdk-17")
            .containsEntry("JAVA_TOOL_OPTIONS", "-Duser.language=en -Duser.country=US")
            .doesNotContainKey("INUBIT_DEV_PASSWORD");
        assertThat(launcher.last().spec().workingDirectory()).isEqualTo(cliHome);
    }

    @Test
    void outputWithoutAVersionLineMakesTheCliUnavailable() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of(
            "JAVA_HOME is set\nError: Could not find or load main class\n", "", 1);

        assertThatThrownBy(() -> probe(launcher).probe(server()))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
                assertThat(e.error().node()).contains(ID);
                assertThat(e.error().excerpt()).hasValueSatisfying(excerpt ->
                    assertThat(excerpt).contains("Could not find or load main class"));
            });
    }

    @Test
    void theProbeUsesTheCliTimeout() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0).hanging();
        EffectiveNodeConfig server = TestNodeConfig.node().cliHome(cliHome)
            .cliJavaHome(Path.of("/opt/jdk-17")).cliTimeout(Duration.ofMillis(200)).build();

        assertThatThrownBy(() -> probe(launcher).probe(server))
            .isInstanceOfSatisfying(ToolErrorException.class,
                e -> assertThat(e.error().code()).isEqualTo(ErrorCode.TIMEOUT));
    }

    @Test
    void matchingVersionsGiveNoWarning() {
        CliVersion cli = new CliVersion("8.1.17", Optional.of("4.0.1"), Optional.of("8.1.17"));

        assertThat(CliVersionProbe.mismatchWarning(ID, cli, "8.1.17")).isEmpty();
    }

    @Test
    void aDifferentServerVersionGivesAWarning() {
        CliVersion cli = new CliVersion("8.1.14", Optional.of("4.0.1"), Optional.of("8.1.14"));

        assertThat(CliVersionProbe.mismatchWarning(ID, cli, "8.1.17"))
            .hasValueSatisfying(warning -> assertThat(warning)
                .contains("dev/node1", "8.1.14", "8.1.17", "cli.home"));
    }

    @Test
    void aVersionMismatchWithinTheSupportedRangeStillWarns() {
        CliVersion cli = new CliVersion("8.1.17", Optional.of("4.0.1"), Optional.of("8.1.17"));

        assertThat(CliVersionProbe.mismatchWarning(ID, cli, "8.1.14")).isPresent();
    }
}
