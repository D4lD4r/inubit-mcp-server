package de.dadecker.inubit.mcp.adapter.cli.v74;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliClientProbe;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.CliClientOptions;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 025 T020 (research D-11): the recorded 7.4 help ({@code fixtures/v7_4/cli/help.*}, the
 * real attachment) read by {@link CliClientProbe}. The 7.4 client lists {@code --execCommand} and
 * neither a trust-store nor a host-name-verification option (input for spec FR-029 and feature
 * 012 FR-012a). The production matrix runs no {@code -h} on 7.4 while nothing is verified on it
 * (research D-9), so this test uses a matrix whose 7.4 entry is CLI-only without notes.
 */
@Timeout(30)
class V74HelpContractTest {

    private static final NodeId NODE = NodeId.parse("legacy74/node1");

    /** A 7.4 entry that is CLI-only without the "not yet verified" notes. */
    private static final CapabilityMatrix VERIFIED_74 = new CapabilityMatrix(List.of(
        new CapabilityMatrix.Entry(new VersionRange(7, 4, 7, 4), Map.of(),
            Map.of(Capability.MONITORING, Set.of(Channel.CLI),
                Capability.PROCESS_DIAGNOSIS, Set.of(Channel.CLI)),
            true)),
        Map.of());

    @TempDir
    Path cliHome;

    /** StartCLI is only launched with a Java runtime ({@code cli.javaHome} or JAVA_HOME). */
    @TempDir
    Path javaHome;

    @Test
    void theSevenFourHelpListsExecCommandButNoTrustStoreOrHostNameOption() throws IOException {
        Path bin = Files.createDirectories(cliHome.resolve("bin"));
        Files.writeString(bin.resolve("startcli.sh"), "#!/bin/sh\n");
        Files.writeString(bin.resolve("startcli.bat"), "@echo off\r\n");
        EffectiveNodeConfig server = TestNodeConfig.node().id(NODE.toString())
            .versionLine(VersionSetting.V7_4).cliHome(cliHome).cliJavaHome(javaHome).build();
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("v7_4", "help");
        CliClientProbe probe = new CliClientProbe(
            TestCliRunners.forHost(launcher, Map.of("PATH", "/usr/bin"),
                new CliResources("acme", PrivateFiles.forHost())),
            id -> server, VERIFIED_74,
            new MutableClock(Instant.parse("2026-11-01T10:00:00Z")));

        Optional<CliClientOptions> options = probe.optionsFor(NODE, Duration.ofSeconds(10));

        assertThat(new String(FakeProcessLauncher.fixture("v7_4", "help.stdout"),
            StandardCharsets.UTF_8)).contains("--execCommand");
        assertThat(launcher.launchCount()).isEqualTo(1);
        assertThat(launcher.last().spec().startCliArguments()).containsExactly("-h");
        // execCommand true shows the help was read, not the fail-closed all-absent result
        assertThat(options).hasValueSatisfying(read -> {
            assertThat(read.execCommand()).as("--execCommand").isTrue();
            assertThat(read.trustStoreFilePath()).as("--trustStoreFilePath").isFalse();
            assertThat(read.disableHostNameVerification())
                .as("--disableHostNameVerification").isFalse();
        });
    }
}
