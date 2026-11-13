package de.dadecker.inubit.mcp.adapter.cli.admin;

import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.storeLabels;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliOutcome;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.CliSettingsSnapshot;
import de.dadecker.inubit.mcp.domain.model.CoverageState;
import de.dadecker.inubit.mcp.domain.model.DbConnectionTolerance;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InterfaceCoverage;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.UnavailablePart;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T025 (feature 022, US1, research "Live recording T014", L-1, L-5): the StartCLI settings reads
 * against the recordings of an 8.1.17 server in {@code fixtures/v8_1/cli/admin-settings-*}.
 *
 * <ul>
 *   <li>The recorded stdin session through {@link CliSettingsRunner} and
 *       {@code CliSessionParser.STANDARD}, with the client's echo of each command and of
 *       {@code exit}. The runner never sends {@code license -td}/{@code -ts} (covered by
 *       {@code GET /license}, research D-6), so their two blocks are cut from the recorded output
 *       and their lines from the recorded stdin; everything else is replayed as recorded,
 *       stderr and exit code 1 included.
 *   <li>The three root-only reads, refused for the profile's account: in the session each costs
 *       only its part (FR-013); in script mode the refusal is neither a login failure nor a missing
 *       "CLI login access" right, so the credential guard never pauses on it. Their coverage rows
 *       are {@code excluded} on 8.1 and 9.0 (owner decision 2026-11-13), so the version gate never
 *       sends them.
 * </ul>
 */
@Timeout(30)
class SettingsCliV81ContractTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final String USER = "jdoe";
    private static final String PASSWORD = "S3cr3t-settings-pw";
    private static final String ROOT_ONLY = "Only root user can use this command.";
    private static final String ROOT_REASON = "needs the INUBIT root user";
    private static final String CUT_FROM = "CLI|jdoe@inubit-dev-1.example.test> license -td\n";
    private static final String CUT_TO = "CLI|jdoe@inubit-dev-1.example.test> exit\n";

    @TempDir
    Path cliHome;

    private final CredentialGuard guard = new CredentialGuard(storeLabels("INUBIT", DEV),
        new MutableClock(Instant.parse("2026-11-13T05:00:00Z")));

    @BeforeEach
    void installFakeCli() throws IOException {
        FakeStartCli.install(cliHome);
    }

    private CliSettingsRunner runner(FakeProcessLauncher launcher) {
        CliRunner cli = TestCliRunners.forHost(launcher, Map.of("PATH", Platform.searchPath()),
            new CliResources("acme", PrivateFiles.forHost()));
        NodeCredentials credentials = new NodeCredentials(DEV,
            Optional.of(new SourcedValue<>(USER, "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
        return new CliSettingsRunner(TestNodeConfig.node()
            .baseUrl("https://inubit-dev-1.example.test:8443").cliHome(cliHome)
            .cliJavaHome(Path.of("/opt/jdk-17")).build(), credentials, guard, cli,
            new CliOutputClassifier(new SecretScrubber(), storeLabels("INUBIT", DEV), false));
    }

    /** The recorded session output without the blocks of {@code license -td} and {@code -ts}. */
    private static String sessionWithoutLicenseBlocks() {
        String stdout = FakeProcessLauncher.fixtureText("admin-settings-session.stdout");
        assertThat(stdout).contains(CUT_FROM).endsWith(CUT_TO);
        return stdout.substring(0, stdout.indexOf(CUT_FROM)) + CUT_TO;
    }

    private static CliResult scriptRun(String fixtureCase) {
        return new CliResult(
            Integer.parseInt(FakeProcessLauncher.fixtureText(fixtureCase + ".exit").strip()),
            FakeProcessLauncher.fixtureText(fixtureCase + ".stdout"),
            FakeProcessLauncher.fixtureText(fixtureCase + ".stderr"), Duration.ofSeconds(2), false,
            CliRunner.MAX_OUTPUT_BYTES);
    }

    @Test
    void theRecordedSessionGivesTheToleranceAndTheTimeAndCostsOnlyTheRefusedParts() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of(sessionWithoutLicenseBlocks(),
            FakeProcessLauncher.fixtureText("admin-settings-session.stderr"),
            Integer.parseInt(FakeProcessLauncher.fixtureText("admin-settings-session.exit")
                .strip()));

        CliSettingsSnapshot settings = runner(launcher).read(Set.of(UnavailablePart.THREADS,
            UnavailablePart.PRIVILEGED_WORKFLOWS, UnavailablePart.DB_CONNECTION_TOLERANCE,
            UnavailablePart.SERVER_TIME));

        assertThat(new String(launcher.last().stdinBytes(), StandardCharsets.UTF_8))
            .as("the recorded stdin without the license lines")
            .isEqualTo(FakeProcessLauncher.fixtureText("admin-settings-session.stdin")
                .replace("<password>", PASSWORD).replace("license -td\n", "")
                .replace("license -ts\n", ""));
        assertThat(settings.dbConnectionTolerance())
            .contains(new DbConnectionTolerance(20, 5, 1800));
        assertThat(settings.serverTime()).contains("Fri Nov 13 05:48:53 CET 2026");
        assertThat(settings.workflowThreads()).isEmpty();
        assertThat(settings.privilegedWorkflows()).isEmpty();
        assertThat(settings.unavailable()).extracting(UnavailablePart::part)
            .containsExactly(UnavailablePart.THREADS, UnavailablePart.PRIVILEGED_WORKFLOWS);
        assertThat(settings.unavailable()).allSatisfy(part ->
            assertThat(part.reason()).startsWith("UNEXPECTED_RESPONSE: "));
    }

    /** The parts the gate admits on 8.1 read the same blocks in a session of their own. */
    @Test
    void theRecordedBlocksOfTheAdmittedPartsAreReadAlone() {
        String recorded = sessionWithoutLicenseBlocks();
        String stdout = recorded.substring(0, recorded.indexOf("CLI|"))
            + recorded.substring(recorded.indexOf(
                "CLI|jdoe@inubit-dev-1.example.test> dbconnectiontolerance show"));
        FakeProcessLauncher launcher = FakeProcessLauncher.of(stdout, "", 0);

        CliSettingsSnapshot settings = runner(launcher).read(Set.of(
            UnavailablePart.DB_CONNECTION_TOLERANCE, UnavailablePart.SERVER_TIME));

        assertThat(new String(launcher.last().stdinBytes(), StandardCharsets.UTF_8))
            .isEqualTo(PASSWORD + "\ndbconnectiontolerance show\ndate\nexit\n");
        assertThat(settings.dbConnectionTolerance())
            .contains(new DbConnectionTolerance(20, 5, 1800));
        assertThat(settings.serverTime()).contains("Fri Nov 13 05:48:53 CET 2026");
        assertThat(settings.unavailable()).isEmpty();
    }

    /** The script-mode answers (L-5) carry the same forms as the session blocks. */
    @Test
    void theRecordedScriptAnswersHaveTheSessionForms() {
        assertThat(DbToleranceParser.parse(AdminCliFixtures.answer("admin-settings-db-tolerance")))
            .isEqualTo(new DbConnectionTolerance(20, 5, 1800));
        assertThat(ServerDateParser.parse(AdminCliFixtures.answer("admin-settings-date")))
            .isEqualTo("Fri Nov 13 05:52:06 CET 2026");
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin-settings-threads-refused",
        "admin-settings-privileged-percentage-refused", "admin-settings-privileged-list-refused"})
    void aRootOnlyRefusalIsNeitherALoginFailureNorAMissingCliRight(String fixtureCase) {
        CliResult refused = scriptRun(fixtureCase);

        assertThat(refused.exitCode()).isEqualTo(1);
        assertThat(refused.stdout()).contains(ROOT_ONLY);
        assertThat(CliOutputClassifier.isLoginFailure(refused)).isFalse();
        assertThat(CliOutputClassifier.isMissingCliRight(refused)).isFalse();
        assertThat(new CliOutputClassifier(new SecretScrubber()).classify(refused))
            .isInstanceOfSatisfying(CliOutcome.Failure.class, failure -> assertThat(
                failure.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE));
    }

    /** Owner decision 2026-11-13: the root-only reads are excluded on both lines. */
    @ParameterizedTest
    @ValueSource(strings = {"threads showNumberOfWorkflowThreads",
        "privilegedWorkflows showThreadsPercentage", "privilegedWorkflows showWorkflowList"})
    void theRootOnlyEntriesAreExcludedOn81And90(String entry) {
        for (MajorMinor line : List.of(new MajorMinor(8, 1), new MajorMinor(9, 0))) {
            assertThat(InterfaceCoverage.production().state(Channel.CLI, entry, line))
                .contains(new CoverageState.Excluded(ROOT_REASON));
        }
    }
}
