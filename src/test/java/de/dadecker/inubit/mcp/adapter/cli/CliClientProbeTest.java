package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.CliClientOptions;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * T017 (feature 011, research D-8, FR-012a): the local {@code startcli.sh -h} probe and the Java
 * version of the client runtime, for {@code cliOnly} nodes only, with the per-node cache.
 */
@Timeout(30)
class CliClientProbeTest {

    private static final NodeId ID = NodeId.parse("legacy/node1");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** A client whose help lists the trust-store, host-name and command options. */
    private static final String HELP_WITH_OPTIONS = """
        usage: startcli [options] [url]
         -u <user>                       user name for the login
         -h                              print this help
         --trustStoreFilePath <file>     trust store for TLS connections
         --disableHostNameVerification   skip the host name check
         --execCommand <command>         run one command and exit
        """;

    @TempDir
    Path cliHome;

    @TempDir
    Path javaHome;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-01T10:00:00Z"));
    private final Map<String, String> parentEnvironment = new HashMap<>(Map.of("PATH", "/usr/bin"));
    private Path script;
    private EffectiveNodeConfig server;

    @BeforeEach
    void installFakeCli() throws IOException {
        script = Files.createDirectories(cliHome.resolve("bin")).resolve("startcli.sh");
        Files.writeString(script, "#!/bin/sh\n");
        server = node(VersionSetting.V6_1).cliJavaHome(javaHome).build();
        release(javaHome, "JAVA_VERSION=\"1.8.0_292\"");
    }

    private TestNodeConfig node(VersionSetting version) {
        return TestNodeConfig.node().id(ID.toString()).versionLine(version).cliHome(cliHome);
    }

    private static void release(Path home, String javaVersionLine) throws IOException {
        Files.writeString(home.resolve("release"),
            "IMPLEMENTOR=\"Example\"\n" + javaVersionLine + "\nOS_NAME=\"Linux\"\n");
    }

    private CliClientProbe probe(FakeProcessLauncher launcher) {
        return new CliClientProbe(new CliRunner(launcher, parentEnvironment, false,
            new CliResources("acme")), id -> server, CapabilityMatrix.production(), clock);
    }

    @Test
    void aSixOneClientIsProbedWithMinusHAndNoServerCredentialsOrStdin() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("v6_1", "help");

        Optional<CliClientOptions> options = probe(launcher).optionsFor(ID, TIMEOUT);

        // the reconstructed 6.1 help documents neither trust-store, host-name nor command option
        assertThat(options).contains(
            new CliClientOptions(false, false, false, Optional.of(8)));
        assertThat(launcher.launchCount()).isEqualTo(1);
        assertThat(launcher.last().spec().command()).containsExactly(script.toString(), "-h");
        assertThat(launcher.last().stdinBytes()).isEmpty();
        assertThat(launcher.last().stdinClosed()).isTrue();
    }

    @Test
    void theOptionsTheHelpListsArePresent() throws IOException {
        release(javaHome, "JAVA_VERSION=\"17.0.2\"");
        FakeProcessLauncher launcher = FakeProcessLauncher.of(HELP_WITH_OPTIONS, "", 0);

        assertThat(probe(launcher).optionsFor(ID, TIMEOUT)).contains(
            new CliClientOptions(true, true, true, Optional.of(17)));
    }

    @Test
    void anUnreadableHelpMeansEveryOptionAbsent() {
        FakeProcessLauncher launcher =
            FakeProcessLauncher.of("--trustStoreFilePath --disableHostNameVerification\n", "", 0);

        // no login option listed: not a help text this probe can read (fail closed)
        assertThat(probe(launcher).optionsFor(ID, TIMEOUT)).contains(
            new CliClientOptions(false, false, false, Optional.of(8)));
    }

    @Test
    void aFailedHelpRunMeansEveryOptionAbsent() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of(HELP_WITH_OPTIONS, "", 1);

        assertThat(probe(launcher).optionsFor(ID, TIMEOUT)).contains(
            new CliClientOptions(false, false, false, Optional.of(8)));
    }

    @Test
    void aHelpRunThatTimesOutMeansEveryOptionAbsent() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0).hanging();

        assertThat(probe(launcher).optionsFor(ID, Duration.ofMillis(200))).contains(
            new CliClientOptions(false, false, false, Optional.of(8)));
    }

    @Test
    void anEightOneNodeStartsNoHelpRun() {
        server = node(VersionSetting.V8_1).cliJavaHome(javaHome).build();
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("v6_1", "help");

        assertThat(probe(launcher).optionsFor(ID, TIMEOUT)).isEmpty();
        assertThat(launcher.launchCount()).isZero();
    }

    @Test
    void nodesNotPinnedToACliOnlyVersionStartNoHelpRun() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("v6_1", "help");
        CliClientProbe probe = probe(launcher);

        for (VersionSetting version : List.of(VersionSetting.AUTO, VersionSetting.V9_X,
            new VersionSetting.Pinned(InubitVersion.parse("8.1.17").orElseThrow()))) {
            server = node(version).cliJavaHome(javaHome).build();
            assertThat(probe.optionsFor(ID, TIMEOUT)).as(version.toString()).isEmpty();
        }
        assertThat(launcher.launchCount()).isZero();
    }

    @Test
    void aConcreteSixOnePinIsProbed() {
        server = node(new VersionSetting.Pinned(InubitVersion.parse("6.1.0.89-HF1").orElseThrow()))
            .cliJavaHome(javaHome).build();
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("v6_1", "help");

        assertThat(probe(launcher).optionsFor(ID, TIMEOUT)).isPresent();
        assertThat(launcher.launchCount()).isEqualTo(1);
    }

    @Test
    void readOptionsAreCachedPerNode() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of(HELP_WITH_OPTIONS, "", 0);
        CliClientProbe probe = probe(launcher);

        Optional<CliClientOptions> first = probe.optionsFor(ID, TIMEOUT);
        clock.advance(Duration.ofHours(1));

        assertThat(probe.optionsFor(ID, TIMEOUT)).isEqualTo(first);
        assertThat(launcher.launchCount()).isEqualTo(1);
    }

    @Test
    void aFailedRunIsNotCachedAndRetriedAtMostOncePer60Seconds() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("not a help text\n", "", 0);
        CliClientProbe probe = probe(launcher);

        probe.optionsFor(ID, TIMEOUT);
        clock.advance(Duration.ofSeconds(59));
        assertThat(probe.optionsFor(ID, TIMEOUT)).contains(
            new CliClientOptions(false, false, false, Optional.of(8)));
        assertThat(launcher.launchCount()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(1));
        probe.optionsFor(ID, TIMEOUT);
        assertThat(launcher.launchCount()).isEqualTo(2);
    }

    @Test
    void aJavaSevenRuntimeHasFeatureVersionSeven() throws IOException {
        release(javaHome, "JAVA_VERSION=\"1.7.0_80\"");

        assertThat(probe(FakeProcessLauncher.replaying("v6_1", "help")).optionsFor(ID, TIMEOUT))
            .map(CliClientOptions::javaFeatureVersion).contains(Optional.of(7));
    }

    @Test
    void withoutCliJavaHomeTheParentsJavaHomeIsRead(@TempDir Path ownJdk) throws IOException {
        release(ownJdk, "JAVA_VERSION=\"21.0.5\"");
        parentEnvironment.put("JAVA_HOME", ownJdk.toString());
        server = node(VersionSetting.V6_1).build();

        assertThat(probe(FakeProcessLauncher.replaying("v6_1", "help")).optionsFor(ID, TIMEOUT))
            .map(CliClientOptions::javaFeatureVersion).contains(Optional.of(21));
    }

    @Test
    void anUnreadableRuntimeVersionIsAbsent() throws IOException {
        Files.delete(javaHome.resolve("release"));
        CliClientProbe withoutRelease = probe(FakeProcessLauncher.replaying("v6_1", "help"));

        assertThat(withoutRelease.optionsFor(ID, TIMEOUT))
            .map(CliClientOptions::javaFeatureVersion).contains(Optional.empty());

        release(javaHome, "JAVA_VERSION=\"unknown\"");
        CliClientProbe withGarbage = probe(FakeProcessLauncher.replaying("v6_1", "help"));
        assertThat(withGarbage.optionsFor(ID, TIMEOUT))
            .map(CliClientOptions::javaFeatureVersion).contains(Optional.empty());
    }
}
