package de.dadecker.inubit.mcp.application;

import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.environmentLabels;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliClientProbe;
import de.dadecker.inubit.mcp.adapter.cli.CliConcurrencyLimiter;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.v61.V61Connection;
import de.dadecker.inubit.mcp.adapter.cli.v61.V61HealthAdapter;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.CliHealthPort;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * An INUBIT 6.1 node behind the real {@link V61HealthAdapter} (T026) with a fake StartCLI, for
 * the {@code get_health} tests of feature 011 (T027): the reconstructed {@code -h} answers the
 * client-option probe, {@link #session} every other launch (a StartCLI session). StartCLI runs
 * over http with {@code allowInsecureHttp}, so no TLS handshake precedes a session.
 */
final class FakeV61Node {

    static final String PASSWORD = "S3cr3t-v61-health-pw";

    final FakeProcessLauncher help = FakeProcessLauncher.replaying("v6_1", "help");
    final FakeProcessLauncher session;
    private final CliHealthPort adapter;

    /**
     * @param dir        an empty directory for the StartCLI and Java installation
     * @param session    answers every StartCLI session
     * @param cliTimeout the node's {@code cliTimeout}
     * @param clock      the clock of the node's credential guard
     */
    FakeV61Node(NodeId id, Path dir, FakeProcessLauncher session, Duration cliTimeout,
        Clock clock) {
        this.session = session;
        Path cliHome = dir.resolve("cli");
        Path javaHome = dir.resolve("java");
        try {
            Files.writeString(Files.createDirectories(cliHome.resolve("bin"))
                .resolve("startcli.sh"), "#!/bin/sh\n");
            Files.writeString(Files.createDirectories(javaHome).resolve("release"),
                "JAVA_VERSION=\"1.8.0_292\"\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        EffectiveNodeConfig server = TestNodeConfig.node().id(id.toString())
            .versionLine(VersionSetting.V6_1).baseUrl("http://inubit-legacy-1.example.test:8000")
            .allowInsecureHttp(true).cliHome(cliHome).cliJavaHome(javaHome)
            .cliTimeout(cliTimeout).build();
        NodeCredentials credentials = new NodeCredentials(id,
            Optional.of(new SourcedValue<>("jdoe", "INUBIT_LEGACY_NODE1_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_LEGACY_NODE1_PASSWORD")),
            Optional.empty(), Optional.empty());
        ProcessLauncher launcher = spec -> spec.command().size() == 2
            && spec.command().get(1).equals("-h") ? help.launch(spec) : session.launch(spec);
        CliRunner runner = new CliRunner(launcher, Map.of("PATH", "/usr/bin"), false,
            new CliResources("acme"));
        this.adapter = new V61HealthAdapter(id, new V61Connection(server, credentials,
                new CredentialGuard(environmentLabels("INUBIT", id), clock), runner,
                new CliClientProbe(runner, node -> server, CapabilityMatrix.production(), clock),
                new CliConcurrencyLimiter(CapabilityMatrix.production())),
            new CliOutputClassifier(new SecretScrubber(), server.secretLabels()));
    }

    CliHealthPort adapter() {
        return adapter;
    }

    /** Every StartCLI launch of this node: option probes and sessions. */
    int launches() {
        return help.launchCount() + session.launchCount();
    }
}
