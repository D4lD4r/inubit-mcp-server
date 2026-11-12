package de.dadecker.inubit.mcp.adapter.cli.admin;

import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.storeLabels;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.StartCliPinProbe;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.CliSettingsSnapshot;
import de.dadecker.inubit.mcp.domain.model.ClientCheck;
import de.dadecker.inubit.mcp.domain.model.ClientKey;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.DbConnectionTolerance;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.PrivilegedWorkflows;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.UnavailablePart;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.ClientCheckLookup;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * T019 (feature 022, US1, research D-7, FR-013): the StartCLI settings reads run in one stdin
 * session bounded by {@code cliTimeout}; each block goes to its parser, and a block that cannot
 * be read marks only its part unavailable. The session output is scripted from the reconstructed
 * {@code admin-settings-*} fixtures with the documented prompt ({@code CliSessionParser.STANDARD};
 * the recorded form follows with T014/T025).
 */
@Timeout(30)
class CliSettingsRunnerTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final String USER = "jdoe";
    private static final String PASSWORD = "S3cr3t-settings-pw";
    private static final String PROMPT = "CLI|jdoe@inubit-dev-1.example.test> ";
    private static final Set<String> ALL_PARTS = Set.of(UnavailablePart.THREADS,
        UnavailablePart.PRIVILEGED_WORKFLOWS, UnavailablePart.DB_CONNECTION_TOLERANCE,
        UnavailablePart.SERVER_TIME);

    private static final List<String> THREADS =
        AdminCliFixtures.block("admin-settings-threads-reconstructed");
    private static final List<String> PERCENTAGE =
        AdminCliFixtures.block("admin-settings-privileged-percentage-reconstructed");
    private static final List<String> LIST =
        AdminCliFixtures.block("admin-settings-privileged-list-reconstructed");
    private static final List<String> TOLERANCE =
        AdminCliFixtures.block("admin-settings-db-tolerance-reconstructed");
    private static final List<String> DATE =
        AdminCliFixtures.block("admin-settings-date-reconstructed");

    /**
     * SYNTHETIC (to be confirmed by T014, L-1): the vendor documents only that StartCLI needs the
     * role System Administrator or the right "CLI login access" (docs/research/
     * inubit-interfaces.md §2.1), not the refusal text. Reconstructed as a login failure whose
     * text names the right, the form that would otherwise be taken for wrong credentials.
     */
    private static final String MISSING_CLI_RIGHT = "Password: \n"
        + "ERROR 08:00:01,234 [main] @Start@LoginFailure@@@The user jdoe has no CLI login access"
        + " right.@End@\n";

    @TempDir
    Path cliHome;

    private final CredentialGuard guard = new CredentialGuard(storeLabels("INUBIT", DEV),
        new MutableClock(Instant.parse("2026-11-12T08:00:00Z")));

    @BeforeEach
    void installFakeCli() throws IOException {
        FakeStartCli.install(cliHome);
    }

    private TestNodeConfig node() {
        return TestNodeConfig.node().baseUrl("https://inubit-dev-1.example.test:8443")
            .cliHome(cliHome).cliJavaHome(Path.of("/opt/jdk-17"));
    }

    private CliSettingsRunner runner(FakeProcessLauncher launcher) {
        return runner(node().build(), TestCliRunners.forHost(launcher,
            Map.of("PATH", Platform.searchPath()),
            new CliResources("acme", PrivateFiles.forHost())));
    }

    private CliSettingsRunner runner(EffectiveNodeConfig server, CliRunner cli) {
        NodeCredentials credentials = new NodeCredentials(DEV,
            Optional.of(new SourcedValue<>(USER, "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
        return new CliSettingsRunner(server, credentials, guard, cli,
            new CliOutputClassifier(new SecretScrubber(), storeLabels("INUBIT", DEV), false));
    }

    /** The session's stdout: the password prompt, one prompt per block, the prompt of exit. */
    @SafeVarargs
    private static String session(List<String>... blocks) {
        StringBuilder out = new StringBuilder("Password: \n");
        for (List<String> block : blocks) {
            out.append(PROMPT).append(String.join("\n", block)).append('\n');
        }
        return out.append(PROMPT).toString();
    }

    private static String stdin(FakeProcessLauncher launcher) {
        return new String(launcher.last().stdinBytes(), StandardCharsets.UTF_8);
    }

    private static ToolError errorOf(ThrowingCallable call) {
        ToolError[] error = new ToolError[1];
        assertThatThrownBy(call).isInstanceOfSatisfying(ToolErrorException.class,
            e -> error[0] = e.error());
        return error[0];
    }

    @Test
    void theCommandsAreTheOnesTheParsersRead() {
        assertThat(CliSettingsRunner.THREADS.text()).isEqualTo(ThreadsParser.COMMAND);
        assertThat(CliSettingsRunner.PRIVILEGED_PERCENTAGE.text())
            .isEqualTo(PrivilegedWorkflowsParser.PERCENTAGE_COMMAND);
        assertThat(CliSettingsRunner.PRIVILEGED_LIST.text())
            .isEqualTo(PrivilegedWorkflowsParser.LIST_COMMAND);
        assertThat(CliSettingsRunner.DB_TOLERANCE.text()).isEqualTo(DbToleranceParser.COMMAND);
        assertThat(CliSettingsRunner.DATE.text()).isEqualTo(ServerDateParser.COMMAND);
    }

    @Test
    void allPartsComeFromOneSessionOnStdin() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of(
            session(THREADS, PERCENTAGE, LIST, TOLERANCE, DATE), "", 0);

        CliSettingsSnapshot settings = runner(launcher).read(ALL_PARTS);

        assertThat(settings.workflowThreads()).contains(16L);
        assertThat(settings.privilegedWorkflows()).contains(new PrivilegedWorkflows(20,
            List.of("Fictitious-Workflow-0001", "Fictitious Workflow 0002"), false));
        assertThat(settings.dbConnectionTolerance())
            .contains(new DbConnectionTolerance(25, 1, 180));
        assertThat(settings.serverTime()).contains("Thu Nov 12 14:03:27 CET 2026");
        assertThat(settings.unavailable()).isEmpty();
        assertThat(launcher.launchCount()).isEqualTo(1);
        assertThat(stdin(launcher)).isEqualTo(PASSWORD + "\n"
            + "threads showNumberOfWorkflowThreads\n"
            + "privilegedWorkflows showThreadsPercentage\n"
            + "privilegedWorkflows showWorkflowList\n"
            + "dbconnectiontolerance show\n"
            + "date\n"
            + "exit\n");
        assertThat(launcher.last().spec().startCliArguments()).doesNotContain("--execCommand")
            .noneMatch(argument -> argument.contains(PASSWORD));
    }

    @Test
    void onlyTheCommandsOfTheRequestedPartsAreSent() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of(session(DATE), "", 0);

        CliSettingsSnapshot settings = runner(launcher).read(Set.of(UnavailablePart.SERVER_TIME));

        assertThat(stdin(launcher)).isEqualTo(PASSWORD + "\ndate\nexit\n");
        assertThat(settings.serverTime()).contains("Thu Nov 12 14:03:27 CET 2026");
        assertThat(settings.workflowThreads()).isEmpty();
        assertThat(settings.privilegedWorkflows()).isEmpty();
        assertThat(settings.dbConnectionTolerance()).isEmpty();
        assertThat(settings.unavailable()).isEmpty();
    }

    @Test
    void noPartStartsNoSessionAndAnotherPartIsRefused() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of(session(), "", 0);

        assertThat(runner(launcher).read(Set.of()))
            .isEqualTo(new CliSettingsSnapshot(Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), List.of()));
        assertThatThrownBy(() -> runner(launcher).read(Set.of(UnavailablePart.LICENSE)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(launcher.launchCount()).isZero();
    }

    /** FR-013: an empty block (the command printed nothing) costs only its own part. */
    @Test
    void aMissingBlockMarksOnlyItsPartUnavailable() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of(
            session(THREADS, PERCENTAGE, LIST, List.of(), DATE), "", 0);

        CliSettingsSnapshot settings = runner(launcher).read(ALL_PARTS);

        assertThat(settings.dbConnectionTolerance()).isEmpty();
        assertThat(settings.unavailable()).singleElement().satisfies(part -> {
            assertThat(part.part()).isEqualTo(UnavailablePart.DB_CONNECTION_TOLERANCE);
            assertThat(part.reason()).startsWith("UNEXPECTED_RESPONSE: ")
                .contains("dbconnectiontolerance show");
        });
        assertThat(settings.workflowThreads()).contains(16L);
        assertThat(settings.privilegedWorkflows()).isPresent();
        assertThat(settings.serverTime()).isPresent();
    }

    /**
     * FR-013 ("command unknown in that version"): an unknown answer is never guessed; either block
     * of the privileged workflows costs that part.
     */
    @Test
    void anUnknownAnswerMarksOnlyItsPartUnavailable() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of(
            session(List.of("Command not found."), PERCENTAGE, List.of("Command not found."),
                TOLERANCE, DATE), "", 0);

        CliSettingsSnapshot settings = runner(launcher).read(ALL_PARTS);

        assertThat(settings.unavailable()).extracting(UnavailablePart::part)
            .containsExactly(UnavailablePart.THREADS, UnavailablePart.PRIVILEGED_WORKFLOWS);
        assertThat(settings.unavailable()).allSatisfy(part ->
            assertThat(part.reason()).startsWith("UNEXPECTED_RESPONSE: "));
        assertThat(settings.workflowThreads()).isEmpty();
        assertThat(settings.privilegedWorkflows()).isEmpty();
        assertThat(settings.dbConnectionTolerance()).isPresent();
        assertThat(settings.serverTime()).isPresent();
    }

    /** Without one prompt per command the blocks cannot be told apart: the session fails. */
    @Test
    void aMissingPromptFailsTheSession() {
        String stdout = "Password: \n" + PROMPT + String.join("\n", THREADS) + "\n"
            + String.join("\n", DATE) + "\n" + PROMPT;
        FakeProcessLauncher launcher = FakeProcessLauncher.of(stdout, "", 0);

        ToolError error = errorOf(() -> runner(launcher).read(
            Set.of(UnavailablePart.THREADS, UnavailablePart.SERVER_TIME)));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.node()).contains(DEV);
    }

    /**
     * Edge case "missing right": StartCLI's refusal for a user without "CLI login access" names
     * the right and is no login failure, so the credential guard does not pause the node either.
     */
    @Test
    void aMissingCliLoginRightIsForbiddenNamingTheRight() {
        FakeProcessLauncher refused = FakeProcessLauncher.of(MISSING_CLI_RIGHT, "", 1);

        ToolError error = errorOf(() -> runner(refused).read(ALL_PARTS));

        assertThat(error.code()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(error.message()).contains("CLI login access");
        assertThat(error.node()).contains(DEV);

        FakeProcessLauncher next = FakeProcessLauncher.of(session(DATE), "", 0);
        assertThat(runner(next).read(Set.of(UnavailablePart.SERVER_TIME)).serverTime())
            .isPresent();
        assertThat(next.launchCount()).isEqualTo(1);
    }

    @Test
    void wrongCredentialsStayALoginFailure() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("Password: \nERROR 08:00:01,234"
            + " [main] @Start@LoginFailure@@@The user does not exist or password does not"
            + " match.@End@\n", "", 1);

        ToolError error = errorOf(() -> runner(launcher).read(ALL_PARTS));

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.node()).contains(DEV);
    }

    @Test
    void theSessionIsBoundedByCliTimeout() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of(session(DATE), "", 0).hanging();
        CliSettingsRunner runner = runner(node().cliTimeout(Duration.ofMillis(300)).build(),
            TestCliRunners.forHost(launcher, Map.of("PATH", Platform.searchPath()),
                new CliResources("acme", PrivateFiles.forHost())));

        ToolError error = errorOf(() -> runner.read(Set.of(UnavailablePart.SERVER_TIME)));

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.message()).contains("300 ms");
        assertThat(error.nextStep()).contains("cliTimeout");
        assertThat(error.node()).contains(DEV);
    }

    /** As in script mode (feature 014, R-15): a tls.pinPerServer stage checks the pin first. */
    @Test
    void aRefusedPinStartsNoSession() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of(session(DATE), "", 0);
        AtomicInteger checks = new AtomicInteger();
        StartCliPinProbe refusing = server -> {
            checks.incrementAndGet();
            throw new ToolErrorException(ToolError.of(ErrorCode.TLS_ERROR,
                "The certificate at the StartCLI address of " + server.id() + " does not match",
                "Another server answers", "Check the pin").withNode(server.id()));
        };
        CliRunner cli = TestCliRunners.forHost(launcher, Map.of("PATH", Platform.searchPath()),
            new CliResources("acme", PrivateFiles.forHost()), refusing);

        ToolError error = errorOf(() -> runner(pinPerServer(node().build()), cli)
            .read(Set.of(UnavailablePart.SERVER_TIME)));

        assertThat(error.code()).isEqualTo(ErrorCode.TLS_ERROR);
        assertThat(checks).hasValue(1);
        assertThat(launcher.launchCount()).isZero();
    }

    /** As in script mode (feature 017, D-4): the launch guard refuses before the pin probe. */
    @Test
    void anExcludedClientIsRefusedBeforeThePinProbe() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of(session(DATE), "", 0);
        AtomicInteger probes = new AtomicInteger();
        StartCliPinProbe counting = server -> probes.incrementAndGet();
        InubitVersion nine = InubitVersion.parse("9.0.7").orElseThrow();
        ClientCheck excluding = new ClientCheck.Checked(new ClientVersion(
            InubitVersion.parse("8.1.17").orElseThrow(), InubitVersion.parse("4.0.1"),
            InubitVersion.parse("8.1.17")));
        ClientKey key = new ClientKey(cliHome.toString(), Path.of("/opt/jdk-17").toString());
        CliRunner cli = TestCliRunners.forHost(launcher, Map.of("PATH", Platform.searchPath()),
            new CliResources("acme", PrivateFiles.forHost()), counting, id -> Optional.of(nine),
            (node, client) -> client.equals(key)
                ? new ClientCheckLookup.Recorded.ForClient(excluding)
                : ClientCheckLookup.Recorded.Absent.OTHER_CLIENTS_ONLY);

        ToolError error = errorOf(() -> runner(
            pinPerServer(node().versionLine(VersionSetting.AUTO).build()), cli)
            .read(Set.of(UnavailablePart.SERVER_TIME)));

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.node()).contains(DEV);
        assertThat(probes).hasValue(0);
        assertThat(launcher.launchCount()).isZero();
    }

    @Test
    void missingCredentialsLaunchNothing() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of(session(DATE), "", 0);
        CliSettingsRunner runner = new CliSettingsRunner(node().build(),
            new NodeCredentials(DEV, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty()),
            guard, TestCliRunners.forHost(launcher, Map.of("PATH", Platform.searchPath()),
                new CliResources("acme", PrivateFiles.forHost())),
            new CliOutputClassifier(new SecretScrubber()));

        ToolError error = errorOf(() -> runner.read(Set.of(UnavailablePart.SERVER_TIME)));

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(launcher.launchCount()).isZero();
    }

    /** {@code server} as a server of a stage marked with {@code tls.pinPerServer}. */
    private static EffectiveNodeConfig pinPerServer(EffectiveNodeConfig server) {
        EffectiveNodeConfig.Tls tls = server.tls();
        return new EffectiveNodeConfig(server.id(), server.production(), server.baseUrl(),
            server.allowInsecureHttp(), server.versionLine(), server.write(),
            new EffectiveNodeConfig.Tls(tls.trustStore(), tls.disableHostnameVerification(),
                tls.pinnedCertificateSha256(), true),
            server.cli(), server.inventory(), server.timeout(), server.cliTimeout(),
            server.cliExportTimeout(), server.hangingThreshold(), server.confirmationTtl(),
            server.credentialPrefix(), server.development(), server.secretPlan(),
            server.archivesProductionOptIn(), server.queueEntryRead(), server.serverLogRead(),
            server.cliSources());
    }
}
