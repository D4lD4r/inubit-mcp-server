package de.dadecker.inubit.mcp.adapter.os;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Feature 011 T004/T005 review r1: a tool whose descendant keeps a pipe open after the exit. */
class ToolRunTest {

    @Test
    void anOpenPipeAfterTheExitIsNoOutputButATimeoutAndKillsTheTree() throws Exception {
        PipedOutputStream heldOpen = new PipedOutputStream();
        PipedInputStream stdout = new PipedInputStream(heldOpen);
        AtomicBoolean killed = new AtomicBoolean();
        ProcessLauncher launcher = spec -> new ProcessLauncher.LaunchedProcess() {
            @Override
            public OutputStream stdin() {
                return OutputStream.nullOutputStream();
            }

            @Override
            public InputStream stdout() {
                return stdout;
            }

            @Override
            public InputStream stderr() {
                return new ByteArrayInputStream(new byte[0]);
            }

            @Override
            public boolean waitFor(Duration timeout) {
                return true;
            }

            @Override
            public int exitValue() {
                return 1;
            }

            @Override
            public boolean isAlive() {
                return false;
            }

            @Override
            public void destroy() {
            }

            @Override
            public boolean treeAlive() {
                return !killed.get();
            }

            @Override
            public void destroyForcibly() {
                killed.set(true);
            }
        };

        ToolRun.Outcome outcome = ToolRun.run(launcher, List.of("/usr/bin/secret-tool"), Map.of(),
            Path.of("/tmp"), Duration.ofSeconds(5));

        heldOpen.close();
        assertThat(outcome).isInstanceOf(ToolRun.Outcome.TimedOut.class);
        assertThat(killed).isTrue();
    }

    @Test
    void passesOnlyTheNamedVariables() {
        assertThat(ToolRun.allowed(Map.of("HOME", "/h", "SECRET", "x"), List.of("HOME", "USER")))
            .containsExactly(Map.entry("HOME", "/h"));
    }
}
