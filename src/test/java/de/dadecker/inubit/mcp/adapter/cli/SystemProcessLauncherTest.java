package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchedProcess;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.Secret;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** The real {@link ProcessBuilder} launcher; uses POSIX tools, so it is skipped on Windows. */
@DisabledOnOs(OS.WINDOWS)
@Timeout(30)
class SystemProcessLauncherTest {

    @TempDir
    Path directory;

    private final SystemProcessLauncher launcher = new SystemProcessLauncher();

    private static String read(LaunchedProcess process) throws IOException {
        return new String(process.stdout().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void theEnvironmentIsExactlyTheGivenMap() throws Exception {
        LaunchedProcess process = launcher.launch(new LaunchSpec(List.of("/usr/bin/env"),
            Map.of("ONLY_THIS", "1"), directory));

        String output = read(process);

        assertThat(process.waitFor(Duration.ofSeconds(10))).isTrue();
        assertThat(output.strip().lines().toList()).containsExactly("ONLY_THIS=1");
    }

    @Test
    void theWorkingDirectoryIsApplied() throws Exception {
        LaunchedProcess process = launcher.launch(new LaunchSpec(List.of("/bin/pwd"),
            Map.of(), directory));

        assertThat(Path.of(read(process).strip()).toRealPath())
            .isEqualTo(directory.toRealPath());
    }

    @Test
    void stdinIsPipedAndArgumentsAreNotInterpretedByAShell() throws Exception {
        LaunchedProcess process = launcher.launch(new LaunchSpec(
            List.of("/bin/echo", "$HOME", "a;b", "'x'"), Map.of("HOME", "/nowhere"), directory));

        assertThat(read(process).strip()).isEqualTo("$HOME a;b 'x'");

        LaunchedProcess cat = launcher.launch(new LaunchSpec(List.of("/bin/cat"), Map.of(),
            directory));
        try (OutputStream stdin = cat.stdin()) {
            stdin.write("line\n".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(read(cat)).isEqualTo("line\n");
        assertThat(cat.waitFor(Duration.ofSeconds(10))).isTrue();
        assertThat(cat.exitValue()).isZero();
    }

    @Test
    void destroyAlsoTerminatesDescendants() throws Exception {
        LaunchedProcess process = launcher.launch(new LaunchSpec(
            List.of("/bin/sh", "-c", "sleep 60 & echo $!; wait"), Map.of("PATH", "/bin:/usr/bin"),
            directory));
        BufferedReader stdout = new BufferedReader(new InputStreamReader(process.stdout(),
            StandardCharsets.UTF_8));
        long child = Long.parseLong(stdout.readLine().strip());
        assertThat(ProcessHandle.of(child)).hasValueSatisfying(
            handle -> assertThat(handle.isAlive()).isTrue());

        process.destroy();

        assertThat(process.waitFor(Duration.ofSeconds(10))).isTrue();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false)
            && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @Test
    void aGrandchildIgnoringTermIsKilledWhenTheCliCallTimesOut() throws Exception {
        // fake startcli.sh: the root exits on TERM, its child ignores TERM and keeps running
        Path script = Files.createDirectories(directory.resolve("bin")).resolve("startcli.sh");
        Files.writeString(script, "#!/bin/sh\n"
            + "sh -c 'trap \"\" TERM; echo $$ > grandchild.pid; while :; do sleep 1; done' &\n"
            + "wait\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        CliRunner runner = new CliRunner(launcher, Map.of("PATH", "/bin:/usr/bin"), false,
            new CliResources("acme"));
        EffectiveNodeConfig server = TestNodeConfig.node().cliHome(directory)
            .cliJavaHome(Path.of("/no-jdk-needed")).build();
        Path pidFile = directory.resolve("grandchild.pid");

        try {
            assertThatThrownBy(() -> runner.run(server, "jdoe", Secret.of("pw"),
                CliCommand.kill("1"), Duration.ofSeconds(1),
                new CredentialGuard(server.credentialVariables(), Clock.systemUTC())))
                .isInstanceOfSatisfying(ToolErrorException.class,
                    e -> assertThat(e.error().code()).isEqualTo(ErrorCode.TIMEOUT));

            long grandchild = Long.parseLong(Files.readString(pidFile).strip());
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (ProcessHandle.of(grandchild).map(ProcessHandle::isAlive).orElse(false)
                && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(ProcessHandle.of(grandchild).map(ProcessHandle::isAlive).orElse(false))
                .as("grandchild ignoring SIGTERM is gone").isFalse();
        } finally {
            if (Files.exists(pidFile)) {
                ProcessHandle.of(Long.parseLong(Files.readString(pidFile).strip()))
                    .ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }
}
