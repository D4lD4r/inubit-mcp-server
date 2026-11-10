package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;

/** Feature 020 T006 (research D-3): the process pipeline extracted from {@link CliRunner}. */
@Timeout(30)
class ProcessRunTest {

    private static final String HELPER = "the queue-entry helper";
    private static final NodeId NODE = NodeId.of("test", "node1");
    private static final LaunchSpec SPEC = new LaunchSpec(
        List.of("/opt/acme/jdk/bin/java", "de.dadecker.inubit.mcp.helper.QueueEntryHelper"),
        Map.of("JAVA_HOME", "/opt/acme/jdk"), Path.of("/opt/acme/client"));

    private final CliResources resources = new CliResources("acme", PrivateFiles.forHost());

    private static byte[] request() {
        return ("qe=1\npassword=" + TestSecrets.CONFIGURED_PASSWORD + "\ntrustStorePassword="
            + TestSecrets.TRUST_STORE_PASSWORD + "\nend\n").getBytes(StandardCharsets.UTF_8);
    }

    private static ToolError errorOf(Executable call) {
        ToolErrorException thrown = (ToolErrorException) org.junit.jupiter.api.Assertions
            .assertThrows(ToolErrorException.class, call);
        return thrown.error();
    }

    private static void assertNoLeak(ToolError error) {
        TestSecrets.assertNoLeak(error.toString());
    }

    @Test
    void stdinIsDeliveredClosedAndZeroFilledAndTheOutputReturned() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("#QE1:abc end\n", "warn\n", 0);
        byte[] stdin = request();
        byte[] expected = stdin.clone();

        CliResult result = new ProcessRun(launcher, resources)
            .run(SPEC, stdin, Duration.ofSeconds(5), HELPER);

        assertThat(launcher.last().spec()).isEqualTo(SPEC);
        assertThat(launcher.last().stdinBytes()).isEqualTo(expected);
        assertThat(launcher.last().stdinClosed()).isTrue();
        assertThat(stdin).containsOnly((byte) 0);
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("#QE1:abc end\n");
        assertThat(result.stderr()).isEqualTo("warn\n");
        assertThat(result.truncated()).isFalse();
        assertThat(result.outputBound()).isEqualTo(ProcessRun.MAX_OUTPUT_BYTES);
        assertThat(result.clientMajor()).isEmpty();
    }

    @Test
    void anEmptyStdinIsClosedAtOnce() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 3);

        CliResult result = new ProcessRun(launcher, resources)
            .run(SPEC, new byte[0], Duration.ofSeconds(5), HELPER);

        assertThat(launcher.last().stdinBytes()).isEmpty();
        assertThat(launcher.last().stdinClosed()).isTrue();
        assertThat(result.exitCode()).isEqualTo(3);
    }

    @Test
    void aTimeoutNamesTheLabelKillsTheProcessAndZeroFillsStdin() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0).hanging();
        byte[] stdin = request();

        ToolError error = errorOf(() -> new ProcessRun(launcher, resources)
            .run(SPEC, stdin, Duration.ofMillis(200), HELPER));

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.message())
            .isEqualTo("The queue-entry helper did not finish within 200 ms");
        assertThat(error.likelyCause()).isEqualTo(
            "The INUBIT server is slow or unreachable, or the queue-entry helper waits for input");
        assertThat(error.nextStep())
            .isEqualTo("Check get_health; raise cliTimeout if the INUBIT server is just slow");
        assertThat(error.node()).isEmpty();
        assertThat(launcher.last().destroyed()).isTrue();
        assertThat(launcher.last().destroyedForcibly()).isFalse();
        assertThat(stdin).containsOnly((byte) 0);
        assertNoLeak(error);
    }

    @Test
    void aProcessIgnoringDestroyIsKilledForciblyAfterTheGrace() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0).ignoringDestroy();

        ToolError error = errorOf(() -> new ProcessRun(launcher, resources)
            .run(SPEC, request(), Duration.ofMillis(200), HELPER));

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(launcher.last().forcibleKillDelay()).hasValueSatisfying(delay ->
            assertThat(delay).isBetween(Duration.ofMillis(1900), Duration.ofMillis(4000)));
        assertThat(ProcessRun.KILL_GRACE).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void aLaunchFailureNamesTheLabelAndStartsNothing() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0)
            .failingToLaunch(new IOException("/opt/acme/jdk/bin/java: Permission denied"));
        byte[] stdin = request();

        ToolError error = errorOf(() -> new ProcessRun(launcher, resources)
            .run(SPEC, stdin, Duration.ofSeconds(5), HELPER));

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message())
            .isEqualTo("The queue-entry helper could not be started (IOException)");
        assertThat(error.likelyCause()).isNotBlank();
        assertThat(error.nextStep()).isNotBlank();
        assertThat(stdin).containsOnly((byte) 0);
        assertNoLeak(error);
        // the reservation was cancelled: the shutdown does not wait for this launch
        assertTimeoutPreemptively(Duration.ofSeconds(2), resources::close);
    }

    @Test
    void outputBeyondOneMebibyteIsCutAndFlagged() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0)
            .producing(ProcessRun.MAX_OUTPUT_BYTES + 4096);

        CliResult result = new ProcessRun(launcher, resources)
            .run(SPEC, request(), Duration.ofSeconds(20), HELPER);

        assertThat(ProcessRun.MAX_OUTPUT_BYTES).isEqualTo(1 << 20);
        assertThat(result.stdout().length()).isEqualTo(ProcessRun.MAX_OUTPUT_BYTES);
        assertThat(result.stderr().length()).isEqualTo(ProcessRun.MAX_OUTPUT_BYTES);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void outputUpToTheBoundIsNotFlagged() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0)
            .producing(ProcessRun.MAX_OUTPUT_BYTES);

        CliResult result = new ProcessRun(launcher, resources)
            .run(SPEC, request(), Duration.ofSeconds(20), HELPER);

        assertThat(result.stdout().length()).isEqualTo(ProcessRun.MAX_OUTPUT_BYTES);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void afterShutdownNothingIsStarted() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0);
        resources.close();
        byte[] stdin = request();

        ToolError error = errorOf(() -> new ProcessRun(launcher, resources)
            .run(SPEC, stdin, Duration.ofSeconds(5), HELPER));

        assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(launcher.launchCount()).isZero();
        assertThat(stdin).containsOnly((byte) 0);
    }

    @Test
    void aShutdownDuringTheRunNamesTheLabel() throws InterruptedException {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0).hanging();
        Thread closer = Thread.ofVirtual().start(() -> {
            while (launcher.launchCount() == 0) {
                Thread.onSpinWait();
            }
            resources.close();
        });

        ToolError error = errorOf(() -> new ProcessRun(launcher, resources)
            .run(SPEC, request(), Duration.ofSeconds(20), HELPER));
        closer.join();

        assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(error.message())
            .isEqualTo("The MCP server is shutting down; the queue-entry helper was stopped");
        assertThat(launcher.last().destroyed()).isTrue();
    }

    @Test
    void startCliKeepsItsTextsWithTheNode() {
        ProcessRun.Call startCli = ProcessRun.Call.of("StartCLI").forNode(NODE)
            .withTimeoutSetting("cliExportTimeout");

        ToolError timeout = errorOf(() -> new ProcessRun(
            FakeProcessLauncher.of("", "", 0).hanging(), resources)
            .run(SPEC, new byte[0], Duration.ofMillis(200), startCli));
        ToolError launch = errorOf(() -> new ProcessRun(FakeProcessLauncher.of("", "", 0)
            .failingToLaunch(new IOException("x")), resources)
            .run(SPEC, new byte[0], Duration.ofSeconds(5), startCli));

        assertThat(timeout.message())
            .isEqualTo("StartCLI did not finish within 200 ms on test/node1");
        assertThat(timeout.likelyCause())
            .isEqualTo("The INUBIT server is slow or unreachable, or StartCLI waits for input");
        assertThat(timeout.nextStep()).isEqualTo(
            "Check get_health; raise cliExportTimeout if the INUBIT server is just slow");
        assertThat(timeout.node()).isEqualTo(Optional.of(NODE));
        assertThat(launch.message()).isEqualTo("StartCLI could not be started (IOException)");
        assertThat(launch.node()).isEqualTo(Optional.of(NODE));
    }

    @Test
    void anInterruptedRunIsCancelledWithTheLabel() throws InterruptedException {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0).hanging();
        ProcessRun.Call startCli = ProcessRun.Call.of("StartCLI").forNode(NODE);
        ProcessRun.Call helper = ProcessRun.Call.of(HELPER);
        ToolError[] errors = new ToolError[2];
        ProcessRun.Call[] calls = {startCli, helper};
        for (int i = 0; i < calls.length; i++) {
            int index = i;
            Thread caller = Thread.ofVirtual().start(() -> {
                try {
                    new ProcessRun(launcher, resources)
                        .run(SPEC, new byte[0], Duration.ofSeconds(20), calls[index]);
                } catch (ToolErrorException e) {
                    errors[index] = e.error();
                }
            });
            while (launcher.launchCount() <= i) {
                Thread.sleep(10);
            }
            Thread.sleep(50);
            caller.interrupt();
            caller.join(5000);
        }

        assertThat(errors[0].code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(errors[0].message()).isEqualTo("The StartCLI call on test/node1 was cancelled");
        assertThat(errors[1].message()).isEqualTo("The queue-entry helper call was cancelled");
        assertThat(launcher.processes()).allSatisfy(process ->
            assertThat(process.destroyed()).isTrue());
    }
}
