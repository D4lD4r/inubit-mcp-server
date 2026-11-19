package de.dadecker.inubit.mcp.adapter.cli.admin;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.cli.CliCommand;
import de.dadecker.inubit.mcp.adapter.cli.CliCommand.TagRemovalKind;
import de.dadecker.inubit.mcp.adapter.cli.CliCommand.TagScope;
import de.dadecker.inubit.mcp.adapter.cli.CliOutcome;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliSessionCommand;
import de.dadecker.inubit.mcp.adapter.cli.CliSessionParser;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.domain.model.CliClientOptions;
import de.dadecker.inubit.mcp.domain.model.Durations;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The StartCLI forms of {@code manage_tags} on an INUBIT 8.1/9.0 node (feature 022, US4, research
 * D-18):
 *
 * <ul>
 *   <li>{@link #activeTag}: {@code user '<user>'} in one stdin session bounded by the node's
 *       {@code cliTimeout}, as {@code read_users} sends it (D-20), read by
 *       {@link ActiveTagParser};
 *   <li>{@link #setActiveTag}, {@link #removeActiveTag} and {@link #removeTag}: one script-mode
 *       run each of {@link CliCommand#tagSetActive}, {@link CliCommand#tagRemoveActive} and
 *       {@link CliCommand#tagRemoval}, bounded by the node's {@code cliExportTimeout} as
 *       {@code tag --tagMove} ({@code CliTagRunner}). Each takes a {@link WritePermit} and is
 *       started by {@link CliRunner} inside {@code WriteLock.handOff}; a permit for another node
 *       starts nothing.
 * </ul>
 *
 * <p>T055 (L-4) recorded the {@code user} session on 8.1.17 for a user without an active tag
 * (fixture {@code admin-tags-user-no-active-tag-session}): the three rows, no {@code Active tag}
 * row, exit code 1 although no command was refused. T055 run 2 recorded only refusals of the
 * removal forms (exit 1, {@code EXECUTION ERROR}: {@code --tagRemove} in a diagram group,
 * {@code --tagDelete} without {@code --tagUser}), which {@link CliCommand#tagRemoval} no longer
 * builds. StartCLI prints no result line for {@code tag --tagMove} (fixture {@code tag_ok}), so a
 * tag form counts as done when it exits with 0 without an {@code n-NOK} message; the active-tag
 * row of {@link ActiveTagParser} is RECONSTRUCTED.
 *
 * <p>A value {@link CliCommand} or {@link CliSessionCommand} cannot send is {@code INVALID_INPUT}
 * before anything is launched. A failed run (credentials, pin, launch, timeout, an exit code
 * other than 0, cut output, a refused login, or an {@code n-NOK} message) is thrown with the node
 * set and the classifier's excerpt, secret-named values masked; the session's exit code counts
 * only if the session stopped before its {@code exit} prompt. A refused {@code user} command
 * ({@code EXECUTION ERROR} in its block, L-1) is {@code NOT_FOUND} naming the user, without the
 * block's reason line; any other unknown {@code user} answer is {@code UNEXPECTED_RESPONSE}. Both
 * have the node set.
 */
public final class CliTagAdminRunner {

    /** The first line of a refused command's block in a session (recorded, L-1). */
    private static final String EXECUTION_ERROR = "EXECUTION ERROR";

    /** As {@code CliUserRunner}: the options of an 8.1/9.0 client in a session. */
    private static final CliClientOptions OPTIONS =
        new CliClientOptions(true, true, true, Optional.empty());

    private final EffectiveNodeConfig server;
    private final NodeCredentials credentials;
    private final CredentialGuard guard;
    private final CliRunner runner;
    private final CliOutputClassifier classifier;

    public CliTagAdminRunner(EffectiveNodeConfig server, NodeCredentials credentials,
        CredentialGuard guard, CliRunner runner, CliOutputClassifier classifier) {
        this.server = Objects.requireNonNull(server, "server");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
    }

    /**
     * The active tag of {@code user}; empty if the answer names none.
     *
     * @throws ToolErrorException see the class comment
     */
    public Optional<String> activeTag(String user) {
        List<CliSessionCommand> commands =
            List.of(checked(() -> CliSessionCommand.command("user").login(user).build()));
        CliRunner.Selection selection = runner.select(server);
        runner.checkAvailable(selection);
        checkCredentials();
        CliResult result;
        try {
            // as in script mode: the launch guard first, then the pin
            runner.checkLaunch(selection);
            // before the guard: a refused address must neither wait for nor count as a login
            runner.checkPin(selection);
            result = runner.runSession(selection, credentials.username().orElseThrow().value(),
                credentials.password().orElseThrow().value(), OPTIONS, commands,
                server.cliTimeout(), CliRunner.MAX_OUTPUT_BYTES, guard);
        } catch (ToolErrorException e) {
            throw failed(e.error());
        }
        // 8.1.17 ends a session that ran to its exit with 1 even when no command was refused (T055,
        // admin-tags-user-no-active-tag-session): only a session cut off before it fails here
        if (result.exitCode() != 0 && !ranToExit(result.stdout(), commands)
            || result.truncated() || CliOutputClassifier.isLoginFailure(result)) {
            // the session prints no n-OK message, so the classifier never calls this a success
            if (classifier.classify(result) instanceof CliOutcome.Failure failure) {
                throw failed(failure.error());
            }
        }
        List<String> block = checked(() ->
            CliSessionParser.STANDARD.blocks(result.stdout(), commands).getFirst());
        if (!block.isEmpty() && block.getFirst().equals(EXECUTION_ERROR)) {
            // the reason line is not shown: its text for a missing login is not recorded (L-6)
            throw new ToolErrorException(ToolError.of(ErrorCode.NOT_FOUND,
                "StartCLI refused 'user' for '" + user + "' on " + server.id(),
                "The login probably does not exist; StartCLI answered EXECUTION ERROR",
                "Check the login with read_users, then retry").withNode(server.id()));
        }
        return checked(() -> ActiveTagParser.parse(user, block));
    }

    /**
     * Sets the active tag of {@code user}.
     *
     * @throws ToolErrorException see the class comment
     */
    public void setActiveTag(String user, String tag, WritePermit permit) {
        run(checked(() -> CliCommand.tagSetActive(tag, user)), permit);
    }

    /**
     * Removes the active tag of {@code user}.
     *
     * @throws ToolErrorException see the class comment
     */
    public void removeActiveTag(String user, WritePermit permit) {
        run(checked(() -> CliCommand.tagRemoveActive(user)), permit);
    }

    /**
     * Removes or deletes {@code tag} in {@code scope}.
     *
     * @throws ToolErrorException {@code INVALID_INPUT} without a kind or scope, or for
     *     {@code --tagRemove} in a diagram group; otherwise see the class comment
     */
    public void removeTag(TagRemovalKind kind, String tag, TagScope scope, WritePermit permit) {
        run(checked(() -> CliCommand.tagRemoval(kind, tag, scope)), permit);
    }

    private void run(CliCommand command, WritePermit permit) {
        permit.requireFor(server.id());
        runner.checkAvailable(server);
        checkCredentials();
        CliResult result;
        try {
            result = runner.run(server, credentials.username().orElseThrow().value(),
                credentials.password().orElseThrow().value(), command, server.cliExportTimeout(),
                "cliExportTimeout", guard, permit);
        } catch (ToolErrorException e) {
            throw failed(e.error());
        }
        if ((result.exitCode() != 0 || result.truncated()
            || CliOutputClassifier.isLoginFailure(result)
            || !classifier.parse(result).nokMessages().isEmpty())
            && classifier.classify(result) instanceof CliOutcome.Failure failure) {
            throw failed(failure.error());
        }
    }

    private void checkCredentials() {
        if (!credentials.complete()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.AUTH_FAILED,
                "No INUBIT credentials are configured for " + server.id()
                    + ", so StartCLI cannot log in",
                "The username or password variable for " + server.id() + " is not set",
                guard.fixCredentials()).withNode(server.id()));
        }
    }

    /** Whether the session's blocks split: one prompt per command and the empty exit block. */
    private static boolean ranToExit(String stdout, List<CliSessionCommand> commands) {
        try {
            CliSessionParser.STANDARD.blocks(stdout, commands);
            return true;
        } catch (ToolErrorException e) {
            return false;
        }
    }

    /** {@code value}'s result, a {@link ToolErrorException} with the node set. */
    private <T> T checked(Supplier<T> value) {
        try {
            return value.get();
        } catch (ToolErrorException e) {
            throw failed(e.error());
        }
    }

    private ToolErrorException failed(ToolError error) {
        return new ToolErrorException(
            error.node().isPresent() ? error : error.withNode(server.id()));
    }

    @Override
    public String toString() {
        return "CliTagAdminRunner[" + server.id() + ", timeout "
            + Durations.human(server.cliExportTimeout()) + "]";
    }
}
