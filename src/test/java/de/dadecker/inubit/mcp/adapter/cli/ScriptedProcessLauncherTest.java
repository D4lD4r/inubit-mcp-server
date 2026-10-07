package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchedProcess;
import de.dadecker.inubit.mcp.config.CredentialVariables;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * T002 (feature 004, research D-22): the scripted fake StartCLI answers a sequence of launches
 * by their {@code --execCommand} line, runs an action per step (e.g. writes the export file),
 * records every launch and fails on a command that was not scripted.
 */
@Timeout(30)
class ScriptedProcessLauncherTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");

    @TempDir
    Path cliHome;
    @TempDir
    Path work;

    @BeforeEach
    void installFakeCli() throws IOException {
        Path script = Files.createDirectories(cliHome.resolve("bin")).resolve("startcli.sh");
        Files.writeString(script, "#!/bin/sh\n");
    }

    @Test
    void launchesAreAnsweredInScriptOrderByTheirCommandLine() throws Exception {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher()
            .expect("export --exportWorkflowUser 'jdoe'").replying("export_modules_sample")
            .expect(Pattern.compile("^import --importFile '[^']+' --importWorkflow .*"))
            .replying("import_modified")
            .expect("export ").replying("export_modules_sample");

        CliResult first = run(cli, "export --exportWorkflowUser 'jdoe' --exportWorkflowGroup 'G'");
        CliResult second = run(cli, "import --importFile '/tmp/x/import.zip' --importWorkflow"
            + " --importUser 'jdoe' --returnProtocol");
        CliResult third = run(cli, "export --exportModule 'M'");

        assertThat(first.stdout()).contains("1-OK: Module exported successfully.");
        assertThat(second.stdout()).contains("Total: 5");
        assertThat(second.exitCode()).isZero();
        assertThat(third.stdout()).contains("1-OK");
        assertThat(cli.execCommands()).containsExactly(
            "export --exportWorkflowUser 'jdoe' --exportWorkflowGroup 'G'",
            "import --importFile '/tmp/x/import.zip' --importWorkflow --importUser 'jdoe'"
                + " --returnProtocol",
            "export --exportModule 'M'");
        assertThat(cli.launches()).hasSize(3);
        cli.verifyComplete();
    }

    @Test
    void aResponseCanBeGivenInline() throws Exception {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher()
            .expect("finger ").replying("out\n", "err\n", 1);

        LaunchedProcess process = cli.launch(spec("finger 'jdoe'"));

        assertThat(process.waitFor(Duration.ofSeconds(1))).isTrue();
        assertThat(process.exitValue()).isEqualTo(1);
        assertThat(new String(process.stdout().readAllBytes(), StandardCharsets.UTF_8))
            .isEqualTo("out\n");
        assertThat(new String(process.stderr().readAllBytes(), StandardCharsets.UTF_8))
            .isEqualTo("err\n");
    }

    @Test
    void aReplyCanBeComputedAtLaunch() throws Exception {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher()
            .expect("import ").replyingWith(spec -> "protocol for "
                + ScriptedProcessLauncher.importFile(spec).getFileName() + "\n");

        CliResult result = run(cli, "import --importFile '/tmp/x/import.zip' --importWorkflow");

        assertThat(result.stdout()).isEqualTo("protocol for import.zip\n");
        assertThat(result.stderr()).startsWith("Picked up JAVA_TOOL_OPTIONS");
        assertThat(result.exitCode()).isZero();
    }

    @Test
    void aWholeReplyCanBeComputedAtLaunch() throws Exception {
        // review I3: a fake server answers NOT_FOUND or the export, depending on its state
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher()
            .expect("export ").answering(spec -> new ScriptedProcessLauncher.Reply(
                "2-NOK: The module x not found\n", "err\n", 1));

        LaunchedProcess process = cli.launch(spec("export --exportModule 'x'"));

        assertThat(process.waitFor(Duration.ofSeconds(1))).isTrue();
        assertThat(process.exitValue()).isEqualTo(1);
        assertThat(new String(process.stdout().readAllBytes(), StandardCharsets.UTF_8))
            .isEqualTo("2-NOK: The module x not found\n");
        assertThat(new String(process.stderr().readAllBytes(), StandardCharsets.UTF_8))
            .isEqualTo("err\n");
    }

    @Test
    void anUnexpectedCommandFailsTheTestAndIsRecorded() {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher()
            .expect("export ").replying("export_modules_sample");

        assertThatThrownBy(() -> cli.launch(spec("import --importFile '/tmp/a.zip'")))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("unexpected StartCLI command")
            .hasMessageContaining("import --importFile '/tmp/a.zip'")
            .hasMessageContaining("expected: export ");
        assertThat(cli.unexpected()).containsExactly("import --importFile '/tmp/a.zip'");
        assertThatThrownBy(cli::verifyComplete).isInstanceOf(AssertionError.class)
            .hasMessageContaining("unexpected");
    }

    @Test
    void aLaunchAfterTheEndOfTheScriptIsUnexpected() throws Exception {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher()
            .expect("export ").replying("export_modules_sample");
        cli.launch(spec("export --exportModule 'M'"));

        assertThatThrownBy(() -> cli.launch(spec("tag --tagMove 'T' --tagUser 'jdoe'")))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("tag --tagMove 'T' --tagUser 'jdoe'")
            .hasMessageContaining("no further launch was scripted");
    }

    @Test
    void verifyCompleteFailsWhileScriptedLaunchesAreMissing() throws Exception {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher()
            .expect("export ").replying("export_modules_sample")
            .expect("import ").replying("import_modified");
        cli.launch(spec("export --exportModule 'M'"));

        assertThatThrownBy(cli::verifyComplete).isInstanceOf(AssertionError.class)
            .hasMessageContaining("1 scripted launch(es) did not happen")
            .hasMessageContaining("import ");
    }

    @Test
    void theStepActionSeesTheLaunchBeforeTheProcessRuns() throws Exception {
        Path exportFile = work.resolve("group.zip");
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher()
            .expect("export ").replying("export_modules_sample")
            .writingExportFile(new byte[] {'P', 'K', 3, 4});

        run(cli, "export --exportModule 'M' --exportFile '" + exportFile + "'");

        assertThat(Files.readAllBytes(exportFile)).containsExactly('P', 'K', 3, 4);
    }

    @Test
    void theImportFileCanBeCapturedWhileItStillExists() throws Exception {
        Path importFile = work.resolve("import.zip");
        Files.write(importFile, new byte[] {1, 2, 3});
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher()
            .expect("import ").replying("import_modified").capturingImportFile();

        run(cli, "import --importFile '" + importFile + "' --importWorkflow");
        Files.delete(importFile);

        assertThat(cli.launches().get(0).importFile()).containsExactly(1, 2, 3);
    }

    @Test
    void aHangingStepEndsInTheRunnersTimeout() {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher()
            .expect("import ").replying("import_timeout").hanging();

        assertThatThrownBy(() -> runner(cli).run(server(), "jdoe", Secret.of("pw"),
            CliCommand.command("export").flag("--includeHistory").build(),
            Duration.ofMillis(200), "cliExportTimeout", guard()))
            .isInstanceOf(AssertionError.class);
        ScriptedProcessLauncher hanging = new ScriptedProcessLauncher()
            .expect("export ").replying("import_timeout").hanging();

        assertThatThrownBy(() -> runner(hanging).run(server(), "jdoe", Secret.of("pw"),
            CliCommand.command("export").flag("--includeHistory").build(),
            Duration.ofMillis(200), "cliExportTimeout", guard()))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> assertThat(e.error().code())
                .isEqualTo(ErrorCode.TIMEOUT));
        assertThat(hanging.launches().get(0).process().destroyed()).isTrue();
    }

    @Test
    void launchesKeepTheirArgumentsAndStdin() throws Exception {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher()
            .expect("export ").replying("export_modules_sample");

        runner(cli).run(server(), "jdoe", Secret.of("pw-123"),
            CliCommand.command("export").flag("--includeHistory").build(),
            Duration.ofSeconds(5), guard());

        ScriptedProcessLauncher.Launch launch = cli.launches().get(0);
        assertThat(launch.execCommand()).isEqualTo("export --includeHistory");
        assertThat(launch.spec().command()).contains("-u", "jdoe");
        assertThat(new String(launch.process().stdinBytes(), StandardCharsets.UTF_8))
            .isEqualTo("pw-123\n");
    }

    // --- helpers --------------------------------------------------------------------------

    private CliResult run(ScriptedProcessLauncher cli, String execCommand) throws Exception {
        LaunchedProcess process = cli.launch(spec(execCommand));
        assertThat(process.waitFor(Duration.ofSeconds(5))).isTrue();
        return new CliResult(process.exitValue(),
            new String(process.stdout().readAllBytes(), StandardCharsets.UTF_8),
            new String(process.stderr().readAllBytes(), StandardCharsets.UTF_8),
            Duration.ZERO, false);
    }

    private LaunchSpec spec(String execCommand) {
        return new LaunchSpec(List.of(cliHome.resolve("bin/startcli.sh").toString(), "-u",
            "jdoe", "--execCommand", execCommand, "https://inubit-dev.example.test:8443/ibis"),
            Map.of(), cliHome);
    }

    private CliRunner runner(ScriptedProcessLauncher cli) {
        return new CliRunner(cli, Map.of("PATH", "/usr/bin"), false, new CliResources("acme"));
    }

    private EffectiveNodeConfig server() {
        return TestNodeConfig.node().cliHome(cliHome).cliJavaHome(Path.of("/opt/jdk-17"))
            .build();
    }

    private static CredentialGuard guard() {
        return new CredentialGuard(new CredentialVariables("INUBIT", DEV),
            new MutableClock(Instant.parse("2026-10-06T08:00:00Z")));
    }
}
