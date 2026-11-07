package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.ClientKey;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.ClientVersion.Comparison;
import de.dadecker.inubit.mcp.domain.model.ClientVersion.Compatibility;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ProbedClient;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code startcli.sh -v} without server or credentials (feature 001 T030, research R-6) behind
 * the port {@code ClientVersionPort} (feature 009 T017, research D-7, FR-002, FR-016).
 */
@Timeout(30)
class CliVersionProbeTest {

    private static final NodeId ID = NodeId.parse("dev/node1");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    Path cliHome;

    private Path script;
    private EffectiveNodeConfig server;

    @BeforeEach
    void installFakeCli() throws IOException {
        script = FakeStartCli.install(cliHome).script();
        server = TestNodeConfig.node().cliHome(cliHome).cliJavaHome(Path.of("/opt/jdk-17"))
            .trustStore(Path.of("/etc/trust.p12")).disableHostnameVerification("AB".repeat(32))
            .build();
    }

    private static final Function<NodeId, Optional<InubitVersion>> NO_FRESH_READ = id -> {
        throw new AssertionError("the probe read the shared version of " + id);
    };

    private CliVersionProbe probe(FakeProcessLauncher launcher) {
        return probe(launcher, new SecretScrubber());
    }

    private CliVersionProbe probe(FakeProcessLauncher launcher, SecretScrubber scrubber) {
        // feature 017 (D-5): the probe chooses by the caller's version, never by a fresh read
        return new CliVersionProbe(TestCliRunners.forHost(launcher,
            Map.of("PATH", Platform.searchPath(), "INUBIT_DEV_PASSWORD", "never-passed"),
                new CliResources("acme", PrivateFiles.forHost()), NO_FRESH_READ), id -> server,
            scrubber);
    }

    /** Replays {@code fixtures/v9_0/cli/<fixtureCase>.*} (FakeProcessLauncher reads v8_1). */
    private static FakeProcessLauncher replayingV90(String fixtureCase) throws IOException {
        return FakeProcessLauncher.of(v90(fixtureCase + ".stdout"), v90(fixtureCase + ".stderr"),
            Integer.parseInt(v90(fixtureCase + ".exit").strip()));
    }

    private static String v90(String name) throws IOException {
        try (InputStream in = CliVersionProbeTest.class.getResourceAsStream(
            "/fixtures/v9_0/cli/" + name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static InubitVersion version(String raw) {
        return InubitVersion.parse(raw).orElseThrow();
    }

    @Test
    void parsesTheClientVersionAndTheSupportedServerRange() {
        ClientVersion client = probe(FakeProcessLauncher.replaying("version"))
            .probe(ID, Optional.empty(), TIMEOUT).version();

        assertThat(client.version()).isEqualTo(version("8.1.17"));
        assertThat(client.supportedFrom()).contains(version("4.0.1"));
        assertThat(client.supportedTo()).contains(version("8.1.17"));
        assertThat(client.compatibleWith(Optional.of(version("8.1.18"))))
            .isEqualTo(Compatibility.COMPATIBLE);
    }

    @Test
    void aClientWithoutARangeIsASuccessWhoseRangeIsNotEvaluable() {
        ClientVersion client = probe(FakeProcessLauncher.replaying("version_no_range"))
            .probe(ID, Optional.empty(), TIMEOUT).version();

        assertThat(client.version()).isEqualTo(version("8.1.17"));
        assertThat(client.supportedFrom()).isEmpty();
        assertThat(client.supportedTo()).isEmpty();
        assertThat(client.compatibleWith(Optional.of(version("8.1.17"))))
            .isEqualTo(Compatibility.NOT_EVALUABLE);
    }

    @Test
    void aRangeInAnUnknownFormIsNotEvaluableRatherThanAFailure() {
        for (String range : new String[] {"from 8.1.17 to 4.0.1.", "from x to 8.1.17."}) {
            ClientVersion client = probe(FakeProcessLauncher.of(
                "CLI 8.1.17\nSupported inubit Process Engine versions " + range + "\n", "", 0))
                .probe(ID, Optional.empty(), TIMEOUT).version();

            assertThat(client.version()).as(range).isEqualTo(version("8.1.17"));
            assertThat(client.supportedFrom()).as(range).isEmpty();
            assertThat(client.supportedTo()).as(range).isEmpty();
        }
    }

    @Test
    void aRangeIsCheckedOnMajorMinorOnlySoAnInvertedPatchStaysEvaluable() {
        // research D-8: the range is reduced to major.minor, as ClientVersion does
        ClientVersion client = probe(FakeProcessLauncher.of("CLI 8.1.17\nSupported inubit"
            + " Process Engine versions from 8.1.17 to 8.1.5.\n", "", 0))
                .probe(ID, Optional.empty(), TIMEOUT).version();

        assertThat(client.supportedFrom()).contains(version("8.1.17"));
        assertThat(client.supportedTo()).contains(version("8.1.5"));
        assertThat(client.compatibleWith(Optional.of(version("8.1.18"))))
            .isEqualTo(Compatibility.COMPATIBLE);
    }

    @Test
    void parsesA90Client() throws IOException {
        ClientVersion client = probe(replayingV90("version"))
            .probe(ID, Optional.empty(), TIMEOUT).version();

        assertThat(client.version()).isEqualTo(version("9.0.7"));
        assertThat(client.supportedFrom()).contains(version("9.0.0"));
        assertThat(client.supportedTo()).contains(version("9.0.7"));
        assertThat(client.compatibleWith(Optional.of(version("8.1.17"))))
            .isEqualTo(Compatibility.INCOMPATIBLE);
    }

    @Test
    void probesTheClientChosenForTheGivenVersionAndReturnsItsKey(@TempDir Path clients)
        throws IOException {
        // feature 017 (T014, research D-5)
        Path client81 = Files.createDirectories(clients.resolve("client-8.1"));
        Path client90 = Files.createDirectories(clients.resolve("client-9.0"));
        FakeStartCli.install(client81);
        FakeStartCli.install(client90);
        server = TestNodeConfig.node().versionLine(VersionSetting.AUTO)
            .cliClient("8.1", client81, Optional.of(Path.of("/opt/jdk-17")))
            .cliClient("9.0", client90, Optional.of(Path.of("/opt/jdk-21"))).build();
        FakeProcessLauncher nine = replayingV90("version");
        FakeProcessLauncher eight = FakeProcessLauncher.replaying("version");

        ProbedClient probed90 = probe(nine).probe(ID, Optional.of(version("9.0.7")), TIMEOUT);
        ProbedClient probed81 = probe(eight).probe(ID, Optional.of(version("8.1.17")), TIMEOUT);

        assertThat(probed90.key())
            .isEqualTo(new ClientKey(client90.toString(), Path.of("/opt/jdk-21").toString()));
        assertThat(probed90.version().version()).isEqualTo(version("9.0.7"));
        assertThat(probed90.version().supportedTo()).contains(version("9.0.7"));
        assertThat(nine.last().spec().workingDirectory()).isEqualTo(client90);
        assertThat(probed81.key())
            .isEqualTo(new ClientKey(client81.toString(), Path.of("/opt/jdk-17").toString()));
        assertThat(probed81.version().version()).isEqualTo(version("8.1.17"));
        assertThat(eight.last().spec().workingDirectory()).isEqualTo(client81);
    }

    @Test
    void theKeyOfANodeWithoutCliClientsIsItsOwnClient() {
        ProbedClient probed = probe(FakeProcessLauncher.replaying("version"))
            .probe(ID, Optional.empty(), TIMEOUT);

        assertThat(probed.key())
            .isEqualTo(new ClientKey(cliHome.toString(), Path.of("/opt/jdk-17").toString()));
    }

    @Test
    void theRangeIsReadWhateverItsCase() {
        // feature 017 (T014, research D-15)
        ClientVersion client = probe(FakeProcessLauncher.of("CLI 9.0.7\nSupported INUBIT"
            + " Process Engine Versions from 9.0.0 to 9.0.7.\n", "", 0))
            .probe(ID, Optional.empty(), TIMEOUT).version();

        assertThat(client.supportedFrom()).contains(version("9.0.0"));
        assertThat(client.supportedTo()).contains(version("9.0.7"));
    }

    @Test
    void runsOnlyDashVWithoutCredentialsServerOrStdin() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("version");

        probe(launcher).probe(ID, Optional.empty(), TIMEOUT).version();

        assertThat(launcher.last().spec().startCliArguments()).containsExactly("-v");
        assertThat(launcher.last().stdinBytes()).isEmpty();
        assertThat(launcher.last().stdinClosed()).isTrue();
        assertThat(launcher.last().spec().environment())
            .containsEntry("JAVA_HOME", Path.of("/opt/jdk-17").toString())
            .containsEntry("JAVA_TOOL_OPTIONS", Platform.windows()
                ? StartCliEnvironment.WINDOWS_JAVA_TOOL_OPTIONS
                : "-Duser.language=en -Duser.country=US")
            .doesNotContainKey("INUBIT_DEV_PASSWORD");
        assertThat(launcher.last().spec().workingDirectory()).isEqualTo(cliHome);
    }

    @Test
    void theProbeIsBoundedByTheGivenTimeoutNotByTheNodesCliTimeout() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0).hanging();
        server = TestNodeConfig.node().cliHome(cliHome).cliJavaHome(Path.of("/opt/jdk-17"))
            .cliTimeout(Duration.ofMinutes(10)).build();

        long start = System.nanoTime();
        assertThatThrownBy(() -> probe(launcher)
            .probe(ID, Optional.empty(), Duration.ofMillis(200)))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.TIMEOUT);
                assertThat(e.error().node()).contains(ID);
            });
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
        assertThat(launcher.last().destroyed()).isTrue();
    }

    @Test
    void withoutAStartCliInstallationTheClientIsUnavailableAndNothingIsLaunched()
        throws IOException {
        Files.delete(script);
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("version");

        assertThatThrownBy(() -> probe(launcher).probe(ID, Optional.empty(), TIMEOUT).version())
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
                assertThat(e.error().node()).contains(ID);
            });
        assertThat(launcher.launchCount()).isZero();
    }

    /** Feature 013 T013: the next step names the script of the platform. */
    @Test
    void onWindowsAFailedProbeNamesTheBatchFileInItsNextStep() throws IOException {
        Files.writeString(cliHome.resolve("bin").resolve("startcli.bat"), "@echo off\r\n");
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("unknown_command");
        CliVersionProbe probe = new CliVersionProbe(TestCliRunners.simulatedWindows(launcher,
            Map.of(), new ProcessContainment.Bound(),
                new CliResources("acme", PrivateFiles.forHost())), id -> server,
            new SecretScrubber());

        assertThatThrownBy(() -> probe.probe(ID, Optional.empty(), TIMEOUT).version())
            .isInstanceOfSatisfying(ToolErrorException.class, e -> assertThat(e.error()
                .nextStep()).isEqualTo("Run bin\\startcli.bat -v in cliHome with that JDK and"
                    + " check the output"));
        assertThat(launcher.launchCount()).isOne();
    }

    @Test
    void withoutACliHomeTheClientIsUnavailableAndNothingIsLaunched() {
        server = TestNodeConfig.node().build();
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("version");

        assertThatThrownBy(() -> probe(launcher).probe(ID, Optional.empty(), TIMEOUT).version())
            .isInstanceOfSatisfying(ToolErrorException.class,
                e -> assertThat(e.error().code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE));
        assertThat(launcher.launchCount()).isZero();
    }

    @Test
    void outputWithoutAVersionLineMakesTheCliUnavailable() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of(
            "JAVA_HOME is set\nError: Could not find or load main class\n", "", 1);

        assertThatThrownBy(() -> probe(launcher).probe(ID, Optional.empty(), TIMEOUT).version())
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
                assertThat(e.error().node()).contains(ID);
                assertThat(e.error().excerpt()).hasValueSatisfying(excerpt ->
                    assertThat(excerpt).contains("Could not find or load main class"));
            });
    }

    @Test
    void anUnparsableVersionMakesTheCliUnavailable() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("CLI unknown\n", "", 0);

        assertThatThrownBy(() -> probe(launcher).probe(ID, Optional.empty(), TIMEOUT).version())
            .isInstanceOfSatisfying(ToolErrorException.class,
                e -> assertThat(e.error().code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE));
    }

    /** FR-016, Constitution II: the excerpt of a failure carries no credential and no URL. */
    @Test
    void theExcerptOfAFailureIsScrubbedAndCarriesNoUrl() {
        SecretScrubber scrubber = new SecretScrubber();
        scrubber.register("s3cr3t-probe-pw");
        FakeProcessLauncher launcher = FakeProcessLauncher.of("""
            JAVA_HOME is set
            Error: login jdoe:s3cr3t-probe-pw rejected by https://inubit.example.test:8443/ibis/rest
            Copyright (C) Virtimo AG
            http://www.virtimo.de
            """, "", 1);

        assertThatThrownBy(() -> probe(launcher, scrubber)
            .probe(ID, Optional.empty(), TIMEOUT).version())
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
                assertThat(e.error().excerpt()).hasValueSatisfying(excerpt -> assertThat(excerpt)
                    .contains("Error: login", SecretScrubber.MASK)
                    .doesNotContain("s3cr3t-probe-pw", "://", "inubit.example.test",
                        "www.virtimo.de"));
                assertThat(e.error().message() + e.error().likelyCause() + e.error().nextStep())
                    .doesNotContain("s3cr3t-probe-pw", "://");
            });
    }

    /** FR-011 replaces the former {@code mismatchWarning}: compare on major.minor.patch. */
    @Test
    void theProbedClientIsComparedWithTheServerOnMajorMinorPatch() {
        ClientVersion client = probe(FakeProcessLauncher.replaying("version"))
            .probe(ID, Optional.empty(), TIMEOUT).version();

        assertThat(client.compare(Optional.of(version("8.1.17")))).isEqualTo(Comparison.MATCH);
        assertThat(client.compare(Optional.of(version("8.1.18")))).isEqualTo(Comparison.MISMATCH);
        assertThat(client.compare(Optional.empty())).isEqualTo(Comparison.NOT_COMPARABLE);
    }
}
