package de.dadecker.inubit.mcp.adapter.cli.admin;

import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.storeLabels;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.ScriptedProcessLauncher;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.TraceLevel;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import de.dadecker.inubit.mcp.domain.port.ClientCheckLookup;
import de.dadecker.inubit.mcp.domain.port.WriteLock;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.RecordingWriteLock;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.TestPermits;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * T057 (feature 022, US4, research D-22): each operation is one script-mode run of its
 * {@code CliCommand} form, scripted by {@link ScriptedProcessLauncher}; the changes start inside
 * the write hand-off of their {@link WritePermit}. No answer is recorded yet (T054, L-2/L-3): the
 * answers below are the RECONSTRUCTED {@code admin-operate-*-reconstructed} blocks behind the
 * recorded 8.1 preamble, or inline RECONSTRUCTED variants of them.
 */
@Timeout(30)
class CliOperationRunnerTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final WritePermit PERMIT = TestPermits.of(DEV, Capability.SERVER_OPERATION);
    private static final String USER = "jdoe";
    private static final String PASSWORD = TestSecrets.CONFIGURED_PASSWORD;
    private static final String CLI_URL =
        "https://inubit-dev-1.example.test:8443/ibis/servlet/IBISSoapServlet";
    private static final String PREAMBLE = "JAVA_HOME is set\nPassword: \n";

    @TempDir
    Path cliHome;

    private final CredentialGuard guard = new CredentialGuard(storeLabels("INUBIT", DEV),
        new MutableClock(Instant.parse("2026-11-17T08:00:00Z")));

    @BeforeEach
    void installFakeCli() throws IOException {
        FakeStartCli.install(cliHome);
    }

    private TestNodeConfig node() {
        return TestNodeConfig.node().baseUrl("https://inubit-dev-1.example.test:8443")
            .cliHome(cliHome).cliJavaHome(Path.of("/opt/jdk-17"));
    }

    private CliOperationRunner runner(ScriptedProcessLauncher cli) {
        return runner(node().build(), cli);
    }

    private CliOperationRunner runner(EffectiveNodeConfig server, ScriptedProcessLauncher cli) {
        return runner(server, TestCliRunners.forHost(cli, Map.of("PATH", Platform.searchPath()),
            new CliResources("acme", PrivateFiles.forHost())));
    }

    /** With {@code lock} as the write lock of the runner; the node runs 8.1.17. */
    private CliOperationRunner runner(EffectiveNodeConfig server, ScriptedProcessLauncher cli,
        WriteLock lock) {
        return runner(server, TestCliRunners.forHost(cli, Map.of("PATH", Platform.searchPath()),
            new CliResources("acme", PrivateFiles.forHost()),
            id -> Optional.of(TestPermits.V8_1_17), ClientCheckLookup.NONE, lock));
    }

    private CliOperationRunner runner(EffectiveNodeConfig server, CliRunner cliRunner) {
        NodeCredentials credentials = new NodeCredentials(DEV,
            Optional.of(new SourcedValue<>(USER, "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
        return new CliOperationRunner(server, credentials, guard, cliRunner,
            new CliOutputClassifier(new SecretScrubber(), storeLabels("INUBIT", DEV), false));
    }

    /** A script step that matches exactly {@code command} and answers {@code stdout}, exit 0. */
    private static ScriptedProcessLauncher step(ScriptedProcessLauncher cli, String command,
        String answer) {
        return cli.expect(Pattern.compile(Pattern.quote(command))).replying(PREAMBLE + answer,
            "", 0);
    }

    /** The RECONSTRUCTED answer block of {@code fixtureCase}, as the client prints it. */
    private static String reconstructed(String fixtureCase) {
        return String.join("\n", AdminCliFixtures.block(fixtureCase)) + "\n";
    }

    private static ToolError errorOf(ThrowingCallable call) {
        ToolError[] error = new ToolError[1];
        assertThatThrownBy(call).isInstanceOfSatisfying(ToolErrorException.class,
            e -> error[0] = e.error());
        TestSecrets.assertNoLeak(error[0].toString());
        return error[0];
    }

    // --- the forms and their answers ------------------------------------------------------------

    @Test
    void theMaintenanceStateIsOneScriptRunWithThePasswordOnlyOnStdin() {
        ScriptedProcessLauncher cli = step(new ScriptedProcessLauncher(), "maintenance -s",
            reconstructed("admin-operate-maintenance-state-reconstructed"));

        assertThat(runner(cli).maintenanceState()).isTrue();

        cli.verifyComplete();
        ScriptedProcessLauncher.Launch launch = cli.launches().getFirst();
        assertThat(launch.spec().startCliArguments()).containsExactly("-u", USER,
            "--execCommand", "maintenance -s", CLI_URL);
        assertThat(new String(launch.process().stdinBytes(), StandardCharsets.UTF_8))
            .isEqualTo(PASSWORD + "\n");
        assertThat(launch.spec().command()).noneMatch(argument -> argument.contains(PASSWORD));
        assertThat(launch.spec().environment().values())
            .noneMatch(value -> value.contains(PASSWORD));
    }

    /** Before, change and re-read as one script (research D-22: the state is read with -s). */
    @Test
    void maintenanceIsSwitchedOnWithExecuteQueuedAndOffWithout() {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher();
        step(cli, "maintenance -s", "Maintenance mode: off\n");
        step(cli, "maintenance on -eq false",
            reconstructed("admin-operate-maintenance-state-reconstructed"));
        step(cli, "maintenance -s", "Maintenance mode: on\n");
        step(cli, "maintenance off", "Maintenance mode: off\n");
        CliOperationRunner runner = runner(cli);

        assertThat(runner.maintenanceState()).isFalse();
        assertThat(runner.switchMaintenance(true, false, PERMIT)).isTrue();
        assertThat(runner.maintenanceState()).isTrue();
        assertThat(runner.switchMaintenance(false, null, PERMIT)).isFalse();

        cli.verifyComplete();
        assertThat(cli.execCommands()).containsExactly("maintenance -s",
            "maintenance on -eq false", "maintenance -s", "maintenance off");
    }

    @Test
    void traceIsSwitchedAndItsLevelSet() {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher();
        step(cli, "trace on", reconstructed("admin-operate-trace-on-reconstructed"));
        step(cli, "trace -l debug", reconstructed("admin-operate-trace-level-reconstructed"));
        step(cli, "trace off", "Trace: off\n");
        CliOperationRunner runner = runner(cli);

        assertThat(runner.switchTrace(true, PERMIT)).isTrue();
        assertThat(runner.setTraceLevel(TraceLevel.DEBUG, PERMIT)).isEqualTo(TraceLevel.DEBUG);
        assertThat(runner.switchTrace(false, PERMIT)).isFalse();

        cli.verifyComplete();
    }

    @Test
    void theMidnightTaskIsTriggeredAndInubitsAnswerReturned() {
        ScriptedProcessLauncher cli = step(new ScriptedProcessLauncher(), "triggerMidnightTask",
            reconstructed("admin-operate-midnight-task-reconstructed"));

        assertThat(runner(cli).triggerMidnightTask(PERMIT)).isEqualTo("Midnight task triggered.");

        cli.verifyComplete();
    }

    @Test
    void deletingTheProcessesInErrorReturnsTheCountInubitReports() {
        ScriptedProcessLauncher cli = step(new ScriptedProcessLauncher(), "processdelete error",
            reconstructed("admin-operate-processdelete-error-reconstructed"));

        assertThat(runner(cli).deleteErrorProcesses(PERMIT)).isEqualTo(40L);

        cli.verifyComplete();
    }

    // --- the write hand-off (feature 017, research D-24; T057 review) ---------------------------

    @Test
    void eachChangeStartsInsideTheHandOffOfItsPermitAndTheStateReadOutside() {
        AtomicBoolean handingOff = new AtomicBoolean();
        RecordingWriteLock lock = new RecordingWriteLock(new WriteLock() {
            @Override
            public <T> T handOff(WritePermit permit,
                Function<Optional<InubitVersion>, T> action) {
                handingOff.set(true);
                try {
                    return action.apply(Optional.of(permit.admitted()));
                } finally {
                    handingOff.set(false);
                }
            }
        });
        List<String> inside = new ArrayList<>();
        List<String> outside = new ArrayList<>();
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher();
        for (String[] form : List.of(new String[] {"maintenance -s", "Maintenance mode: on"},
            new String[] {"maintenance off", "Maintenance mode: off"},
            new String[] {"trace on", "Trace: on"},
            new String[] {"trace -l warn", "Trace level: warn"},
            new String[] {"triggerMidnightTask", "Midnight task triggered."},
            new String[] {"processdelete error", "Deleted processes: 2"})) {
            cli.expect(Pattern.compile(Pattern.quote(form[0])))
                .then(spec -> (handingOff.get() ? inside : outside)
                    .add(ScriptedProcessLauncher.execCommand(spec)))
                .replying(PREAMBLE + form[1] + "\n", "", 0);
        }
        CliOperationRunner runner = runner(node().build(), cli, lock);

        runner.maintenanceState();
        runner.switchMaintenance(false, null, PERMIT);
        runner.switchTrace(true, PERMIT);
        runner.setTraceLevel(TraceLevel.WARN, PERMIT);
        runner.triggerMidnightTask(PERMIT);
        runner.deleteErrorProcesses(PERMIT);

        cli.verifyComplete();
        assertThat(inside).containsExactly("maintenance off", "trace on", "trace -l warn",
            "triggerMidnightTask", "processdelete error");
        assertThat(outside).as("the state read is no write").containsExactly("maintenance -s");
        assertThat(lock.permits()).hasSize(5).allSatisfy(permit ->
            assertThat(permit).isSameAs(PERMIT));
    }

    // --- refused before the launch -----------------------------------------------------------

    @Test
    void aPermitForAnotherNodeLaunchesNothing() {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher();
        WritePermit other = TestPermits.of(NodeId.parse("dev/node2"),
            Capability.SERVER_OPERATION);

        assertThatThrownBy(() -> runner(cli).switchTrace(true, other))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(cli.launches()).isEmpty();
    }

    @Test
    void executeQueuedWithMaintenanceOffLaunchesNothing() {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher();

        assertThat(errorOf(() -> runner(cli).switchMaintenance(false, true, PERMIT)).code())
            .isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(cli.launches()).isEmpty();
    }

    @Test
    void missingCredentialsLaunchNothing() {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher();
        CliOperationRunner runner = new CliOperationRunner(node().build(),
            new NodeCredentials(DEV, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty()),
            guard, TestCliRunners.forHost(cli, Map.of("PATH", Platform.searchPath()),
                new CliResources("acme", PrivateFiles.forHost())),
            new CliOutputClassifier(new SecretScrubber()));

        ToolError error = errorOf(() -> runner.deleteErrorProcesses(PERMIT));

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.node()).contains(DEV);
        assertThat(cli.launches()).isEmpty();
    }

    // --- failed runs and unknown answers (leak check: errorOf) ---------------------------------

    @Test
    void anUnknownAnswerIsUnexpectedForTheNodeWithSecretsMasked() {
        ScriptedProcessLauncher cli = step(new ScriptedProcessLauncher(), "processdelete error",
            "Removed " + TestSecrets.FREE_TEXT_SECRETS + "\n");

        ToolError error = errorOf(() -> runner(cli).deleteErrorProcesses(PERMIT));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.node()).contains(DEV);
        assertThat(error.excerpt()).hasValueSatisfying(
            excerpt -> assertThat(excerpt).contains("Removed"));
    }

    @Test
    void aNokAnswerFailsTheChangeWithTheNode() {
        ScriptedProcessLauncher cli = step(new ScriptedProcessLauncher(), "trace on",
            "1-NOK: Access denied\n");

        ToolError error = errorOf(() -> runner(cli).switchTrace(true, PERMIT));

        assertThat(error.node()).contains(DEV);
        assertThat(error.excerpt()).hasValueSatisfying(
            excerpt -> assertThat(excerpt).contains("1-NOK: Access denied"));
    }

    @Test
    void aFailedRunKeepsAnExcerptWithSecretsMasked() {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher();
        cli.expect(Pattern.compile(Pattern.quote("trace on"))).replying(PREAMBLE
            + "EXECUTION ERROR\nInternal INUBIT error!\n" + TestSecrets.FREE_TEXT_SECRETS + "\n",
            "", 1);

        ToolError error = errorOf(() -> runner(cli).switchTrace(true, PERMIT));

        assertThat(error.node()).contains(DEV);
        assertThat(error.excerpt()).isPresent();
    }

    @Test
    void theRunIsBoundedByCliTimeout() {
        ScriptedProcessLauncher cli = step(new ScriptedProcessLauncher(), "maintenance -s",
            "Maintenance mode: on\n").hanging();

        ToolError error = errorOf(() -> runner(node().cliTimeout(Duration.ofMillis(300)).build(),
            cli).maintenanceState());

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.nextStep()).contains("cliTimeout");
        assertThat(error.node()).contains(DEV);
    }
}
