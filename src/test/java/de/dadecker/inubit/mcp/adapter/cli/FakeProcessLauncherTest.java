package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchedProcess;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * T002 (feature 011, research D-15): {@code FakeProcessLauncher} and
 * {@code ScriptedProcessLauncher} replay a case from the fixture folder of the given version
 * ({@code v8_1} by default, {@code v6_1}).
 */
class FakeProcessLauncherTest {

    private static final LaunchSpec SPEC = new LaunchSpec(
        List.of("startcli.sh", "--execCommand", "replay"), Map.of(), Path.of("."));

    @Test
    void replaysAV61Case() throws Exception {
        LaunchedProcess process = FakeProcessLauncher.replaying("v6_1", "replay_selftest")
            .launch(SPEC);

        assertThat(process.waitFor(Duration.ofSeconds(5))).isTrue();
        assertThat(text(process.stdout().readAllBytes())).isEqualTo("v6_1 replay\n");
        assertThat(text(process.stderr().readAllBytes())).isEmpty();
        assertThat(process.exitValue()).isEqualTo(3);
    }

    @Test
    void replaysFromV81ByDefault() throws Exception {
        LaunchedProcess process = FakeProcessLauncher.replaying("finger_user").launch(SPEC);

        assertThat(process.waitFor(Duration.ofSeconds(5))).isTrue();
        assertThat(process.stdout().readAllBytes())
            .isEqualTo(FakeProcessLauncher.fixture("v8_1", "finger_user.stdout"));
        assertThat(process.exitValue()).isZero();
    }

    @Test
    void aCaseOfOneVersionIsNotFoundInTheOther() {
        assertThatThrownBy(() -> FakeProcessLauncher.replaying("replay_selftest"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("v8_1/cli/replay_selftest.stdout");
        assertThatThrownBy(() -> FakeProcessLauncher.replaying("v6_1", "finger_user"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("v6_1/cli/finger_user.stdout");
    }

    @Test
    void scriptedLauncherRepliesWithAV61Case() throws Exception {
        LaunchedProcess process = new ScriptedProcessLauncher()
            .expect("replay").replying("v6_1", "replay_selftest")
            .launch(SPEC);

        assertThat(process.waitFor(Duration.ofSeconds(5))).isTrue();
        assertThat(text(process.stdout().readAllBytes())).isEqualTo("v6_1 replay\n");
        assertThat(process.exitValue()).isEqualTo(3);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
