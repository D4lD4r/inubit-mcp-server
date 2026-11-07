package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.config.CliChoice.AddressKind;
import de.dadecker.inubit.mcp.config.CliChoice.HomeSource;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.CliClientOptions;
import de.dadecker.inubit.mcp.domain.model.ClientKey;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Feature 017 (T009, research D-3, D-4, D-19): {@link CliRunner} chooses the StartCLI client,
 * JDK and address per call from the node's effective version.
 */
@Timeout(30)
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "POSIX launch; Windows in"
    + " CliRunnerWindowsSelectTest")
class CliRunnerSelectTest {

    private static final String USER = "jdoe";
    private static final String PASSWORD = "S3cr3t-017-pw";
    private static final String BASE_URL = "https://inubit-acme.example.test:8443";
    private static final String SERVLET_URL = BASE_URL + EffectiveNodeConfig.DEFAULT_CLI_PATH;
    private static final Path JDK_17 = Path.of("/opt/jdk-17");
    private static final Path JDK_21 = Path.of("/opt/jdk-21");
    private static final InubitVersion V8_1_17 =
        new InubitVersion(8, 1, OptionalInt.of(17), "8.1.17");
    private static final InubitVersion V9_0_7 =
        new InubitVersion(9, 0, OptionalInt.of(7), "9.0.7");

    @TempDir
    Path root;

    private Path client81;
    private Path client90;
    private final Map<String, String> parentEnv = new HashMap<>();
    private final AtomicReference<Optional<InubitVersion>> version =
        new AtomicReference<>(Optional.empty());
    private final AtomicInteger versionReads = new AtomicInteger();
    private final Function<NodeId, Optional<InubitVersion>> versions = id -> {
        versionReads.incrementAndGet();
        return version.get();
    };
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger runnerLogger;
    private Level previousLevel;

    @BeforeEach
    void install() throws IOException {
        client81 = Files.createDirectories(root.resolve("client-8.1"));
        client90 = Files.createDirectories(root.resolve("client-9.0"));
        FakeStartCli.install(client81);
        FakeStartCli.install(client90);
        parentEnv.put("PATH", Platform.searchPath());
        parentEnv.put("HOME", "/Users/jdoe");
        runnerLogger = (Logger) LoggerFactory.getLogger(CliRunner.class);
        previousLevel = runnerLogger.getLevel();
        runnerLogger.setLevel(Level.DEBUG);
        runnerLogger.setAdditive(false);
        logs.start();
        runnerLogger.addAppender(logs);
    }

    @AfterEach
    void restoreLogger() {
        runnerLogger.detachAppender(logs);
        runnerLogger.setLevel(previousLevel);
        runnerLogger.setAdditive(true);
    }

    private TestNodeConfig node() {
        return TestNodeConfig.node()
            .baseUrl(BASE_URL)
            .versionLine(VersionSetting.AUTO)
            .cliClient("8.1", client81, Optional.of(JDK_17))
            .cliClient("9.0", client90, Optional.of(JDK_21));
    }

    private CliRunner runner(FakeProcessLauncher launcher) {
        return TestCliRunners.forHost(launcher, parentEnv,
            new CliResources("acme", PrivateFiles.forHost()), versions);
    }

    private CredentialGuard guard(EffectiveNodeConfig server) {
        return new CredentialGuard(server.secretLabels(),
            new MutableClock(Instant.parse("2026-10-01T08:00:00Z")));
    }

    private ProcessLauncher.LaunchSpec run(EffectiveNodeConfig server) {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0);
        runner(launcher).run(server, USER, Secret.of(PASSWORD),
            CliCommand.processErrorStart("110190387"), Duration.ofSeconds(5), guard(server));
        return launcher.last().spec();
    }

    @Test
    void anEightOneNodeLaunchesTheEightOneClientAtTheServletAddress() {
        version.set(Optional.of(V8_1_17));

        ProcessLauncher.LaunchSpec spec = run(node().build());

        assertThat(spec.command().get(0))
            .isEqualTo(client81.resolve("bin/startcli.sh").toString());
        assertThat(spec.workingDirectory()).isEqualTo(client81);
        assertThat(spec.environment()).containsEntry("JAVA_HOME", JDK_17.toString());
        assertThat(spec.command()).last().isEqualTo(SERVLET_URL);
    }

    @Test
    void aNineZeroNodeLaunchesTheNineZeroClientWithJdk21AtTheBaseUrl() {
        version.set(Optional.of(V9_0_7));

        ProcessLauncher.LaunchSpec spec = run(node().build());

        assertThat(spec.command().get(0))
            .isEqualTo(client90.resolve("bin/startcli.sh").toString());
        assertThat(spec.workingDirectory()).isEqualTo(client90);
        assertThat(spec.environment()).containsEntry("JAVA_HOME", JDK_21.toString());
        assertThat(spec.command()).last().isEqualTo(BASE_URL);
    }

    @Test
    void anExplicitCliUrlIsKept() {
        String explicit = "https://cli-acme.example.test:9443/custom";
        for (InubitVersion each : List.of(V8_1_17, V9_0_7)) {
            version.set(Optional.of(each));
            ProcessLauncher.LaunchSpec spec = run(node().cliUrl(explicit).build());
            assertThat(spec.command()).last().isEqualTo(explicit);
        }
    }

    @Test
    void theVersionIsReadOncePerCall() throws IOException {
        version.set(Optional.of(V9_0_7));
        EffectiveNodeConfig server = node().build();
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0);
        CliRunner runner = runner(launcher);

        runner.run(server, USER, Secret.of(PASSWORD), CliCommand.processErrorStart("1"),
            Duration.ofSeconds(5), guard(server));
        assertThat(versionReads.getAndSet(0)).as("run").isEqualTo(1);
        runner.runSession(server, USER, Secret.of(PASSWORD),
            new CliClientOptions(true, true, true, Optional.empty()), List.of(),
            Duration.ofSeconds(5), CliRunner.MAX_OUTPUT_BYTES, guard(server));
        assertThat(versionReads.getAndSet(0)).as("runSession").isEqualTo(1);
        runner.execute(server, List.of("-v"), Optional.empty(), Duration.ofSeconds(5));
        assertThat(versionReads.getAndSet(0)).as("execute").isEqualTo(1);
        runner.checkAvailable(server);
        assertThat(versionReads.getAndSet(0)).as("checkAvailable").isEqualTo(1);
        assertThat(launcher.processes()).allSatisfy(process -> assertThat(
            process.spec().command().get(0)).startsWith(client90.toString()));
    }

    @Test
    void selectionNamesTheChoiceTheKeyAndNoWarning() {
        CliRunner runner = runner(FakeProcessLauncher.of("", "", 0));
        EffectiveNodeConfig server = node().build();

        CliRunner.Selection nine = runner.select(server, Optional.of(V9_0_7));

        assertThat(nine.server().cli())
            .isEqualTo(new EffectiveNodeConfig.Cli(URI.create(BASE_URL), Optional.of(client90),
                Optional.of(JDK_21)));
        assertThat(nine.choice().source()).isEqualTo(HomeSource.VERSION_ENTRY);
        assertThat(nine.choice().entry()).contains(new MajorMinor(9, 0));
        assertThat(nine.choice().address()).isEqualTo(AddressKind.BASE_URL);
        assertThat(nine.key()).isEqualTo(new ClientKey(client90.toString(), JDK_21.toString()));
        assertThat(nine.addressWarning()).isEmpty();
        assertThat(versionReads).as("select reads no version").hasValue(0);

        CliRunner.Selection none = runner.select(TestNodeConfig.node().versionLine(
            VersionSetting.AUTO).build(), Optional.of(V9_0_7));
        assertThat(none.key()).isEqualTo(ClientKey.NONE);
        assertThat(none.choice().source()).isEqualTo(HomeSource.NONE);
    }

    @Test
    void theResultCarriesTheMajorTheClientWasChosenFor() {
        // feature 017 (T012, research D-16): the classifier's AUTH_FAILED reads it
        EffectiveNodeConfig server = node().build();
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0);
        CliRunner runner = runner(launcher);

        version.set(Optional.of(V9_0_7));
        CliResult nine = runner.run(server, USER, Secret.of(PASSWORD),
            CliCommand.processErrorStart("110190387"), Duration.ofSeconds(5), guard(server));
        version.set(Optional.of(V8_1_17));
        CliResult eight = runner.run(server, USER, Secret.of(PASSWORD),
            CliCommand.processErrorStart("110190387"), Duration.ofSeconds(5), guard(server));
        version.set(Optional.empty());
        EffectiveNodeConfig withHome = node().cliHome(client81).cliJavaHome(JDK_17).build();
        CliResult unknown = runner.run(withHome, USER, Secret.of(PASSWORD),
            CliCommand.processErrorStart("110190387"), Duration.ofSeconds(5), guard(withHome));

        assertThat(nine.clientMajor()).hasValue(9);
        assertThat(eight.clientMajor()).hasValue(8);
        assertThat(unknown.clientMajor()).isEmpty();
    }

    @Test
    void selectIsIdempotent() {
        CliRunner runner = runner(FakeProcessLauncher.of("", "", 0));
        EffectiveNodeConfig server = node().build();

        for (Optional<InubitVersion> each : List.of(Optional.<InubitVersion>empty(),
            Optional.of(V8_1_17), Optional.of(V9_0_7))) {
            CliRunner.Selection once = runner.select(server, each);
            CliRunner.Selection twice = runner.select(once.server(), each);
            assertThat(twice).isEqualTo(once);
        }
        assertThat(runner.select(runner.select(server, Optional.of(V9_0_7)).server(),
            Optional.of(V8_1_17))).isEqualTo(runner.select(server, Optional.of(V8_1_17)));
    }

    @Test
    void aNodeWithoutCliClientsKeepsItsSettings() {
        // FR-009: a 0.8.0 profile chooses exactly its configured client and address
        CliRunner runner = runner(FakeProcessLauncher.of("", "", 0));
        EffectiveNodeConfig server = TestNodeConfig.node().baseUrl(BASE_URL).cliHome(client81)
            .cliJavaHome(JDK_17).build();

        for (Optional<InubitVersion> each : List.of(Optional.<InubitVersion>empty(),
            Optional.of(V8_1_17))) {
            assertThat(runner.select(server, each).server()).isEqualTo(server);
        }
    }

    @Test
    void anExplicitServletAddressOnNineWarns() {
        CliRunner runner = runner(FakeProcessLauncher.of("", "", 0));
        EffectiveNodeConfig server = node().cliUrl(SERVLET_URL).build();

        CliRunner.Selection nine = runner.select(server, Optional.of(V9_0_7));

        assertThat(nine.choice().address()).isEqualTo(AddressKind.CONFIGURED);
        assertThat(nine.server().cli().url()).isEqualTo(URI.create(SERVLET_URL));
        assertThat(nine.addressWarning()).contains("cli.url of dev/node1 uses"
            + " /ibis/servlet/IBISSoapServlet, which INUBIT 9.0.2 and later removed; remove"
            + " cli.url or set it to the base URL");
        assertThat(runner.select(server, Optional.of(V8_1_17)).addressWarning()).isEmpty();
        assertThat(runner.select(node().cliUrl("https://cli-acme.example.test/x").build(),
            Optional.of(V9_0_7)).addressWarning()).isEmpty();
    }

    @Test
    void eachLaunchLogsTheClientDirectoryAndTheAddressKindButNoUrlOrPassword() {
        version.set(Optional.of(V9_0_7));
        run(node().build());
        version.set(Optional.of(V8_1_17));
        run(node().build());

        List<String> messages = logs.list.stream().map(ILoggingEvent::getFormattedMessage)
            .filter(message -> message.contains("StartCLI client")).toList();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).contains("dev/node1", client90.toString(), "base_url",
            "version_entry");
        assertThat(messages.get(1)).contains(client81.toString(), "servlet_path");
        // leak check
        String all = String.join("\n", logs.list.stream()
            .map(ILoggingEvent::getFormattedMessage).toList());
        assertThat(all).doesNotContain(PASSWORD, "inubit-acme.example.test", "://");
    }
}
