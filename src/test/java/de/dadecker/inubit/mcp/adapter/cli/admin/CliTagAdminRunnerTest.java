package de.dadecker.inubit.mcp.adapter.cli.admin;

import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.storeLabels;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliCommand.TagRemovalKind;
import de.dadecker.inubit.mcp.adapter.cli.CliCommand.TagScope;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher.Answer;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
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
 * T063 (feature 022, US4, research D-18): the active tag of a user is read in one stdin session
 * with {@code user '<user>'}, scripted in the recorded session form ({@code admin-users-session});
 * the tag forms of T062 are script-mode runs scripted by {@link ScriptedProcessLauncher}, started
 * inside the write hand-off of their {@link WritePermit}. No answer of these forms is recorded yet
 * (T055, L-4): the active-tag row is the RECONSTRUCTED
 * {@code admin-tags-user-active-tag-reconstructed}, and the tag forms are answered like the
 * recorded {@code tag --tagMove} ({@code tag_ok}: no result line) or its recorded refusal
 * ({@code tag_repository_user_refused}).
 */
@Timeout(30)
class CliTagAdminRunnerTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final WritePermit PERMIT = TestPermits.of(DEV, Capability.TAG_ADMINISTRATION);
    private static final String USER = "jdoe";
    private static final String PASSWORD = TestSecrets.CONFIGURED_PASSWORD;
    private static final String PROMPT = "CLI|jdoe@inubit-dev-1.example.test> ";
    private static final String PREAMBLE = "JAVA_HOME is set\nPassword: \n";
    private static final TagScope GROUP = new TagScope.DiagramGroup("GRP-01", "technical");

    @TempDir
    Path cliHome;

    private final CredentialGuard guard = new CredentialGuard(storeLabels("INUBIT", DEV),
        new MutableClock(Instant.parse("2026-11-18T08:00:00Z")));

    @BeforeEach
    void installFakeCli() throws IOException {
        FakeStartCli.install(cliHome);
    }

    private TestNodeConfig node() {
        return TestNodeConfig.node().baseUrl("https://inubit-dev-1.example.test:8443")
            .cliHome(cliHome).cliJavaHome(Path.of("/opt/jdk-17"));
    }

    private CliTagAdminRunner runner(ProcessLauncher launcher) {
        return runner(node().build(), TestCliRunners.forHost(launcher, Map.of("PATH",
            Platform.searchPath()), new CliResources("acme", PrivateFiles.forHost())));
    }

    private CliTagAdminRunner runner(EffectiveNodeConfig server, CliRunner cliRunner) {
        NodeCredentials credentials = new NodeCredentials(DEV,
            Optional.of(new SourcedValue<>(USER, "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
        return new CliTagAdminRunner(server, credentials, guard, cliRunner,
            new CliOutputClassifier(new SecretScrubber(), storeLabels("INUBIT", DEV), false));
    }

    /**
     * A session that answers each command line on stdin with the prompt, the echoed command and
     * {@code block} in CRLF lines, then the echoed exit.
     */
    private static FakeProcessLauncher session(List<String> block, int exitCode) {
        return FakeProcessLauncher.of("", "", 0).responding(stdin -> {
            List<String> lines = new String(stdin, StandardCharsets.UTF_8).lines().toList();
            StringBuilder out = new StringBuilder(PREAMBLE);
            for (String command : lines.subList(1, lines.size())) {
                out.append(PROMPT).append(command).append('\n');
                if (!command.equals("exit")) {
                    block.forEach(line -> out.append(line).append("\r\n"));
                }
            }
            return new Answer(out.toString(), exitCode);
        });
    }

    private static String stdin(FakeProcessLauncher launcher) {
        return new String(launcher.last().stdinBytes(), StandardCharsets.UTF_8);
    }

    private static ToolError errorOf(ThrowingCallable call) {
        ToolError[] error = new ToolError[1];
        assertThatThrownBy(call).isInstanceOfSatisfying(ToolErrorException.class,
            e -> error[0] = e.error());
        TestSecrets.assertNoLeak(error[0].toString());
        return error[0];
    }

    // --- the active tag ---------------------------------------------------------------------

    @Test
    void theActiveTagIsReadInOneSessionWithThePasswordAndTheUserCommandOnStdin() {
        FakeProcessLauncher launcher = session(
            AdminCliFixtures.block("admin-tags-user-active-tag-reconstructed"), 0);

        assertThat(runner(launcher).activeTag("jdoe")).contains("REL-1");

        assertThat(launcher.launchCount()).isEqualTo(1);
        assertThat(stdin(launcher)).isEqualTo(PASSWORD + "\nuser 'jdoe'\nexit\n");
    }

    @Test
    void theRecordedAnswerWithoutTheRowNamesNoActiveTag() {
        FakeProcessLauncher launcher = session(AdminCliFixtures.answer("admin-users-user"), 0);

        assertThat(runner(launcher).activeTag("jdoe")).isEmpty();
    }

    @Test
    void aRefusedUserCommandIsNotFoundNamingTheUser() {
        // the refused block of L-1, with a secret in its reason line for the leak check
        FakeProcessLauncher launcher = session(List.of("EXECUTION ERROR",
            "Internal INUBIT error! " + TestSecrets.FREE_TEXT_SECRETS), 1);

        ToolError error = errorOf(() -> runner(launcher).activeTag("jdoe"));

        assertThat(error.code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(error.node()).contains(DEV);
        assertThat(error.message()).contains("'jdoe'");
    }

    @Test
    void anUnknownAnswerIsUnexpectedForTheNode() {
        FakeProcessLauncher launcher = session(List.of("Something else"), 0);

        ToolError error = errorOf(() -> runner(launcher).activeTag("jdoe"));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.node()).contains(DEV);
    }

    @Test
    void aFailedSessionFailsWithTheNode() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of(PREAMBLE
            + "Connection refused " + TestSecrets.FREE_TEXT_SECRETS + "\n", "", 2);

        ToolError error = errorOf(() -> runner(launcher).activeTag("jdoe"));

        assertThat(error.node()).contains(DEV);
    }

    @Test
    void aUserStartCliCannotCarryLaunchesNothing() {
        FakeProcessLauncher launcher = session(List.of(), 0);

        ToolError error = errorOf(() -> runner(launcher).activeTag("j'doe"));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.node()).contains(DEV);
        assertThat(launcher.launchCount()).isZero();
    }

    // --- the tag forms ----------------------------------------------------------------------

    @Test
    void eachTagFormIsOneScriptRunAnsweredLikeTagMove() {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher();
        for (String form : List.of("tag --tagSetActive 'REL-1' --tagUser 'jdoe'",
            "tag --tagRemoveActive --tagUser 'jdoe'",
            "tag --tagRemove 'REL-1' --tagWorkflowGroup 'GRP-01' --tagWorkflowType 'technical'",
            "tag --tagDelete 'REL-1' --tagRepositoryPath '/Root/jdoe/xsd'")) {
            cli.expect(Pattern.compile(Pattern.quote(form))).replying("tag_ok");
        }
        CliTagAdminRunner runner = runner(cli);

        runner.setActiveTag("jdoe", "REL-1", PERMIT);
        runner.removeActiveTag("jdoe", PERMIT);
        runner.removeTag(TagRemovalKind.REMOVE, "REL-1", GROUP, PERMIT);
        runner.removeTag(TagRemovalKind.DELETE, "REL-1",
            new TagScope.RepositoryPath("/Root/jdoe/xsd"), PERMIT);

        cli.verifyComplete();
        ScriptedProcessLauncher.Launch launch = cli.launches().getFirst();
        assertThat(new String(launch.process().stdinBytes(), StandardCharsets.UTF_8))
            .isEqualTo(PASSWORD + "\n");
        assertThat(launch.spec().command()).noneMatch(argument -> argument.contains(PASSWORD));
    }

    @Test
    void eachTagFormStartsInsideTheHandOffOfItsPermit() {
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
        List<Boolean> inside = new ArrayList<>();
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher();
        for (int i = 0; i < 3; i++) {
            cli.expect("tag ").then(spec -> inside.add(handingOff.get())).replying("tag_ok");
        }
        CliTagAdminRunner runner = runner(node().build(), TestCliRunners.forHost(cli,
            Map.of("PATH", Platform.searchPath()), new CliResources("acme", PrivateFiles.forHost()),
            id -> Optional.of(TestPermits.V8_1_17), ClientCheckLookup.NONE, lock));

        runner.setActiveTag("jdoe", "REL-1", PERMIT);
        runner.removeActiveTag("jdoe", PERMIT);
        runner.removeTag(TagRemovalKind.DELETE, "REL-1", GROUP, PERMIT);

        cli.verifyComplete();
        assertThat(inside).containsExactly(true, true, true);
        assertThat(lock.permits()).hasSize(3).allSatisfy(permit ->
            assertThat(permit).isSameAs(PERMIT));
    }

    // --- refused before the launch -----------------------------------------------------------

    @Test
    void aPermitForAnotherNodeLaunchesNothing() {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher();
        WritePermit other = TestPermits.of(NodeId.parse("dev/node2"),
            Capability.TAG_ADMINISTRATION);

        assertThatThrownBy(() -> runner(cli).removeActiveTag("jdoe", other))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(cli.launches()).isEmpty();
    }

    @Test
    void aValueStartCliQuotingCannotCarryLaunchesNothing() {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher();
        CliTagAdminRunner runner = runner(cli);

        for (ThrowingCallable call : List.<ThrowingCallable>of(
            () -> runner.setActiveTag("jdoe", "REL'1", PERMIT),
            () -> runner.removeActiveTag("-jdoe", PERMIT),
            () -> runner.removeTag(TagRemovalKind.REMOVE, "REL-1",
                new TagScope.DiagramGroup("*", "technical"), PERMIT),
            () -> runner.removeTag(TagRemovalKind.REMOVE, "REL-1", null, PERMIT))) {
            ToolError error = errorOf(call);
            assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
            assertThat(error.node()).contains(DEV);
        }
        assertThat(cli.launches()).isEmpty();
    }

    @Test
    void missingCredentialsLaunchNothing() {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher();
        CliTagAdminRunner runner = new CliTagAdminRunner(node().build(),
            new NodeCredentials(DEV, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty()),
            guard, TestCliRunners.forHost(cli, Map.of("PATH", Platform.searchPath()),
                new CliResources("acme", PrivateFiles.forHost())),
            new CliOutputClassifier(new SecretScrubber()));

        for (ThrowingCallable call : List.<ThrowingCallable>of(() -> runner.activeTag("jdoe"),
            () -> runner.setActiveTag("jdoe", "REL-1", PERMIT))) {
            ToolError error = errorOf(call);
            assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
            assertThat(error.node()).contains(DEV);
        }
        assertThat(cli.launches()).isEmpty();
    }

    // --- failed runs (leak check: errorOf) ------------------------------------------------------

    @Test
    void theRecordedRefusalFailsTheChangeWithItsExcerpt() {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher();
        cli.expect("tag --tagRemove").replying("tag_repository_user_refused");

        ToolError error = errorOf(() -> runner(cli).removeTag(TagRemovalKind.REMOVE, "REL-1",
            GROUP, PERMIT));

        assertThat(error.node()).contains(DEV);
        assertThat(error.excerpt()).hasValueSatisfying(excerpt ->
            assertThat(excerpt).contains("Cannot specify user when repository is tagged."));
    }

    @Test
    void aNokAnswerFailsTheChangeWithTheNodeAndSecretsMasked() {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher();
        cli.expect("tag --tagSetActive").replying(PREAMBLE + "1-NOK: Access denied "
            + TestSecrets.FREE_TEXT_SECRETS + "\n", "", 0);

        ToolError error = errorOf(() -> runner(cli).setActiveTag("jdoe", "REL-1", PERMIT));

        assertThat(error.node()).contains(DEV);
        assertThat(error.excerpt()).hasValueSatisfying(
            excerpt -> assertThat(excerpt).contains("1-NOK: Access denied"));
    }

    @Test
    void theTagFormsAreBoundedByCliExportTimeout() {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher();
        cli.expect("tag --tagDelete").hanging();

        ToolError error = errorOf(() -> runner(node().cliExportTimeout(Duration.ofMillis(300))
            .build(), TestCliRunners.forHost(cli, Map.of("PATH", Platform.searchPath()),
                new CliResources("acme", PrivateFiles.forHost())))
            .removeTag(TagRemovalKind.DELETE, "REL-1", GROUP, PERMIT));

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.nextStep()).contains("cliExportTimeout");
        assertThat(error.node()).contains(DEV);
    }
}
