package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 017 (T010, US2 scenario 7, research D-21): on Windows a {@code defaults.cliClients}
 * entry goes through the rules of {@code cli.home} and {@code cli.javaHome} (feature 013).
 */
@Timeout(30)
class CliRunnerWindowsSelectTest {

    private static final String USER = "jdoe";
    private static final String PASSWORD = "S3cr3t-017-win";
    private static final String BASE_URL = "https://inubit-acme.example.test:8443";
    private static final Path JDK_21 = Path.of("C:\\Java\\jdk-21");
    private static final InubitVersion V8_1_17 =
        new InubitVersion(8, 1, OptionalInt.of(17), "8.1.17");
    private static final InubitVersion V9_0_7 =
        new InubitVersion(9, 0, OptionalInt.of(7), "9.0.7");

    @TempDir
    Path root;

    private Path batchFile(Path home) throws IOException {
        Path bat = Files.createDirectories(home.resolve("bin")).resolve("startcli.bat");
        Files.writeString(bat, "@echo off\r\n");
        return bat;
    }

    private TestNodeConfig node() {
        return TestNodeConfig.node().baseUrl(BASE_URL).versionLine(VersionSetting.AUTO);
    }

    private FakeProcessLauncher run(EffectiveNodeConfig server, InubitVersion version) {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0);
        CliRunner runner = TestCliRunners.simulatedWindows(launcher,
            Map.of("JAVA_HOME", "C:\\Java\\jdk-17"),
            new ProcessContainment.Bound(), new CliResources("acme", PrivateFiles.forHost()),
            id -> Optional.of(version));
        runner.run(server, USER, Secret.of(PASSWORD), CliCommand.processErrorStart("110190387"),
            Duration.ofSeconds(5), new CredentialGuard(server.secretLabels(),
                new MutableClock(Instant.parse("2026-10-01T08:00:00Z"))));
        return launcher;
    }

    private ToolError refusal(EffectiveNodeConfig server, InubitVersion version) {
        try {
            FakeProcessLauncher launcher = run(server, version);
            throw new AssertionError("expected a refusal, launched " + launcher.launchCount());
        } catch (ToolErrorException e) {
            assertThat(e.error().toString()).doesNotContain(PASSWORD);
            return e.error();
        }
    }

    @Test
    void aNineZeroEntryLaunchesItsStartCliBat() throws IOException {
        Path client90 = Files.createDirectories(root.resolve("client-9.0"));
        Path bat = batchFile(client90);

        FakeProcessLauncher launcher = run(node()
            .cliClient("9.0", client90, Optional.of(JDK_21)).build(), V9_0_7);

        ProcessLauncher.LaunchSpec spec = launcher.last().spec();
        assertThat(spec.command().get(0))
            .isEqualTo(TestCliRunners.SYSTEM_DIRECTORY + "\\cmd.exe");
        assertThat(String.join(" ", spec.command())).contains(bat.toString());
        assertThat(spec.workingDirectory()).isEqualTo(client90);
        assertThat(spec.environment()).containsEntry("JAVA_HOME", JDK_21.toString());
        assertThat(spec.startCliArguments()).last().isEqualTo(BASE_URL);
    }

    @Test
    void aNetworkEntryHomeIsRefusedAsForCliHome() {
        Path unc = Path.of("\\\\fileserver\\share\\client-9.0");

        ToolError error = refusal(node().cliClient("9.0", unc, Optional.empty()).build(),
            V9_0_7);

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).contains("cli.home is a network path (\\\\…)");
        assertThat(error.nextStep()).contains("local drive");
        assertThat(error.toString()).doesNotContain("fileserver");
    }

    @Test
    void anEntryHomeWithAnInterpretedCharacterIsRefusedWithoutTheValue() {
        Path home = Path.of("C:\\inubit\\client%PATH%");

        ToolError error = refusal(node().cliClient("9.0", home, Optional.empty()).build(),
            V9_0_7);

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).contains("cli.home contains a character the Windows command"
            + " interpreter interprets (percent sign)");
        assertThat(error.toString()).doesNotContain("client%PATH%");
    }

    @Test
    void anEntryJavaHomeWithASemicolonIsRefusedWithoutTheValue() throws IOException {
        Path client90 = Files.createDirectories(root.resolve("client-9.0"));
        batchFile(client90);

        ToolError error = refusal(node().cliClient("9.0", client90,
            Optional.of(Path.of("C:\\Java\\jdk;21"))).build(), V9_0_7);

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).contains("cli.javaHome contains a character the Windows"
            + " command interpreter interprets (semicolon)");
        assertThat(error.toString()).doesNotContain("jdk;21");
    }

    @Test
    void aRefusedEntryDoesNotAffectTheOtherVersion() throws IOException {
        Path client81 = Files.createDirectories(root.resolve("client-8.1"));
        batchFile(client81);
        EffectiveNodeConfig server = node()
            .cliClient("8.1", client81, Optional.empty())
            .cliClient("9.0", Path.of("\\\\fileserver\\share\\client-9.0"), Optional.empty())
            .build();

        FakeProcessLauncher launcher = run(server, V8_1_17);

        assertThat(launcher.last().spec().workingDirectory()).isEqualTo(client81);
        assertThat(launcher.last().spec().startCliArguments()).last()
            .isEqualTo(BASE_URL + EffectiveNodeConfig.DEFAULT_CLI_PATH);
    }
}
