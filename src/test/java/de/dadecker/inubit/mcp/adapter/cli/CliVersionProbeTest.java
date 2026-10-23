package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.ClientVersion.Comparison;
import de.dadecker.inubit.mcp.domain.model.ClientVersion.Compatibility;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
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
        script = Files.createDirectories(cliHome.resolve("bin")).resolve("startcli.sh");
        Files.writeString(script, "#!/bin/sh\n");
        server = TestNodeConfig.node().cliHome(cliHome).cliJavaHome(Path.of("/opt/jdk-17"))
            .trustStore(Path.of("/etc/trust.p12")).disableHostnameVerification("AB".repeat(32))
            .build();
    }

    private CliVersionProbe probe(FakeProcessLauncher launcher) {
        return probe(launcher, new SecretScrubber());
    }

    private CliVersionProbe probe(FakeProcessLauncher launcher, SecretScrubber scrubber) {
        return new CliVersionProbe(new CliRunner(launcher,
            Map.of("PATH", "/usr/bin", "INUBIT_DEV_PASSWORD", "never-passed"), false,
                new CliResources("acme")), id -> server, scrubber);
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
        ClientVersion client = probe(FakeProcessLauncher.replaying("version")).probe(ID, TIMEOUT);

        assertThat(client.version()).isEqualTo(version("8.1.17"));
        assertThat(client.supportedFrom()).contains(version("4.0.1"));
        assertThat(client.supportedTo()).contains(version("8.1.17"));
        assertThat(client.compatibleWith(Optional.of(version("8.1.18"))))
            .isEqualTo(Compatibility.COMPATIBLE);
    }

    @Test
    void aClientWithoutARangeIsASuccessWhoseRangeIsNotEvaluable() {
        ClientVersion client = probe(FakeProcessLauncher.replaying("version_no_range"))
            .probe(ID, TIMEOUT);

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
                .probe(ID, TIMEOUT);

            assertThat(client.version()).as(range).isEqualTo(version("8.1.17"));
            assertThat(client.supportedFrom()).as(range).isEmpty();
            assertThat(client.supportedTo()).as(range).isEmpty();
        }
    }

    @Test
    void aRangeIsCheckedOnMajorMinorOnlySoAnInvertedPatchStaysEvaluable() {
        // research D-8: the range is reduced to major.minor, as ClientVersion does
        ClientVersion client = probe(FakeProcessLauncher.of("CLI 8.1.17\nSupported inubit"
            + " Process Engine versions from 8.1.17 to 8.1.5.\n", "", 0)).probe(ID, TIMEOUT);

        assertThat(client.supportedFrom()).contains(version("8.1.17"));
        assertThat(client.supportedTo()).contains(version("8.1.5"));
        assertThat(client.compatibleWith(Optional.of(version("8.1.18"))))
            .isEqualTo(Compatibility.COMPATIBLE);
    }

    @Test
    void parsesA90Client() throws IOException {
        ClientVersion client = probe(replayingV90("version")).probe(ID, TIMEOUT);

        assertThat(client.version()).isEqualTo(version("9.0.7"));
        assertThat(client.supportedFrom()).contains(version("9.0.0"));
        assertThat(client.supportedTo()).contains(version("9.0.7"));
        assertThat(client.compatibleWith(Optional.of(version("8.1.17"))))
            .isEqualTo(Compatibility.INCOMPATIBLE);
    }

    @Test
    void runsOnlyDashVWithoutCredentialsServerOrStdin() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("version");

        probe(launcher).probe(ID, TIMEOUT);

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
    void theProbeIsBoundedByTheGivenTimeoutNotByTheNodesCliTimeout() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0).hanging();
        server = TestNodeConfig.node().cliHome(cliHome).cliJavaHome(Path.of("/opt/jdk-17"))
            .cliTimeout(Duration.ofMinutes(10)).build();

        long start = System.nanoTime();
        assertThatThrownBy(() -> probe(launcher).probe(ID, Duration.ofMillis(200)))
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

        assertThatThrownBy(() -> probe(launcher).probe(ID, TIMEOUT))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
                assertThat(e.error().node()).contains(ID);
            });
        assertThat(launcher.launchCount()).isZero();
    }

    @Test
    void withoutACliHomeTheClientIsUnavailableAndNothingIsLaunched() {
        server = TestNodeConfig.node().build();
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("version");

        assertThatThrownBy(() -> probe(launcher).probe(ID, TIMEOUT))
            .isInstanceOfSatisfying(ToolErrorException.class,
                e -> assertThat(e.error().code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE));
        assertThat(launcher.launchCount()).isZero();
    }

    @Test
    void outputWithoutAVersionLineMakesTheCliUnavailable() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of(
            "JAVA_HOME is set\nError: Could not find or load main class\n", "", 1);

        assertThatThrownBy(() -> probe(launcher).probe(ID, TIMEOUT))
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

        assertThatThrownBy(() -> probe(launcher).probe(ID, TIMEOUT))
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

        assertThatThrownBy(() -> probe(launcher, scrubber).probe(ID, TIMEOUT))
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
        ClientVersion client = probe(FakeProcessLauncher.replaying("version")).probe(ID, TIMEOUT);

        assertThat(client.compare(Optional.of(version("8.1.17")))).isEqualTo(Comparison.MATCH);
        assertThat(client.compare(Optional.of(version("8.1.18")))).isEqualTo(Comparison.MISMATCH);
        assertThat(client.compare(Optional.empty())).isEqualTo(Comparison.NOT_COMPARABLE);
    }
}
