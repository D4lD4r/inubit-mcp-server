package de.dadecker.inubit.mcp.adapter.cli.admin;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.cli.CliOutcome;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner.ClientOption;
import de.dadecker.inubit.mcp.adapter.cli.CliSessionCommand;
import de.dadecker.inubit.mcp.adapter.cli.CliSessionParser;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.domain.model.CliClientOptions;
import de.dadecker.inubit.mcp.domain.model.Durations;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.LoginEntry;
import de.dadecker.inubit.mcp.domain.model.SecretTextMasker;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.UserFacts;
import de.dadecker.inubit.mcp.domain.model.UserFacts.Block;
import de.dadecker.inubit.mcp.infra.Secret;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The StartCLI reads of {@code read_users} on an INUBIT 8.1/9.0 node (feature 022, US2, research
 * D-7, D-20):
 *
 * <ul>
 *   <li>{@link #read}: the groups and roles of a page of at most {@value #MAX_LOGINS} logins in
 *       one stdin session bounded by the node's {@code cliTimeout}, with
 *       {@code user '<login>'} ({@link UserGroupsParser}) and {@code finger '<login>'}
 *       ({@link FingerParser}) per login, quoted as recorded (T028);
 *   <li>{@link #logins}: one client run, {@code startcli.sh … --who <url>} ({@link WhoParser}),
 *       with only the password on stdin. On 8.1 {@code -who} is neither a session command nor an
 *       {@code --execCommand} argument (T028, T028a). {@code --nwho} is not run: it counts every
 *       connected system, not the logged-in users (owner decision 2026-11-13).
 * </ul>
 *
 * <p>A block its parser cannot read, and a login outside {@link CliSessionCommand}'s value rule
 * (never sent), cost only that user's answer: {@link Block.Unavailable} with the error (FR-013).
 * The parsers' excerpts show no personal value (FR-011). A refused command answers
 * {@code EXECUTION ERROR} in its block and the session ends with exit code 1 (T014, L-1). A
 * failure of the whole session or client run (credentials, pin, launch, timeout, an exit code
 * other than 0 without such a block, cut output, a refused login, a missing "CLI login access"
 * right, not one prompt per command, an unknown {@code --who} answer) is thrown
 * with the node set.
 *
 * <p>Excerpts: a failure of the whole session carries none, because the session's output holds
 * the {@code user}/{@code finger} blocks of every user of the page, with e-mail and name
 * (FR-011); it keeps code, message, cause and next step. A failure of a client run keeps the
 * classifier's or {@link WhoParser}'s excerpt (the {@code --who} table shows no e-mail or name),
 * with secret-named values masked.
 * Credential, pin, launch and timeout errors hold no StartCLI output.
 *
 * <p>What {@code user} answers for a login that does not exist was not recorded (L-6), so it is
 * not recognized here; the caller tells an unknown login from the REST user list first.
 */
public final class CliUserRunner {

    /** The most logins one session reads: the largest page of {@code read_users}. */
    public static final int MAX_LOGINS = 25;

    /** The first line of a refused command's block in a session (recorded, L-1). */
    private static final String EXECUTION_ERROR = "EXECUTION ERROR";

    /** As {@code CliSettingsRunner}: the options of an 8.1/9.0 client in a session. */
    private static final CliClientOptions OPTIONS =
        new CliClientOptions(true, true, true, Optional.empty());

    private final EffectiveNodeConfig server;
    private final NodeCredentials credentials;
    private final CredentialGuard guard;
    private final CliRunner runner;
    private final CliOutputClassifier classifier;

    public CliUserRunner(EffectiveNodeConfig server, NodeCredentials credentials,
        CredentialGuard guard, CliRunner runner, CliOutputClassifier classifier) {
        this.server = Objects.requireNonNull(server, "server");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
    }

    /**
     * Reads the groups and roles of {@code logins} in one session; no sendable login starts
     * nothing.
     *
     * @param logins at most {@value #MAX_LOGINS}, e.g. a page of the REST user list
     * @return one entry per login, in the order given
     * @throws ToolErrorException with the node set, for a failure of the whole session (see the
     *     class comment)
     * @throws IllegalArgumentException for more than {@value #MAX_LOGINS} logins; nothing is
     *     started
     */
    public List<UserFacts> read(List<String> logins) {
        if (logins.size() > MAX_LOGINS) {
            throw new IllegalArgumentException("at most " + MAX_LOGINS + " logins per session");
        }
        List<CliSessionCommand> commands = new ArrayList<>();
        List<Optional<ToolError>> unsendable = new ArrayList<>();
        for (String login : logins) {
            try {
                CliSessionCommand user = command("user", login);
                CliSessionCommand finger = command("finger", login);
                commands.add(user);
                commands.add(finger);
                unsendable.add(Optional.empty());
            } catch (ToolErrorException e) {
                unsendable.add(Optional.of(e.error()));
            }
        }
        List<List<String>> blocks = commands.isEmpty() ? List.of() : session(commands);
        List<UserFacts> users = new ArrayList<>();
        int next = 0;
        for (int i = 0; i < logins.size(); i++) {
            String login = logins.get(i);
            if (unsendable.get(i).isPresent()) {
                ToolError error = unsendable.get(i).get();
                users.add(new UserFacts(login, new Block.Unavailable<>(error),
                    new Block.Unavailable<>(error)));
                continue;
            }
            users.add(new UserFacts(login,
                parse(login, blocks.get(next), UserGroupsParser::parse),
                parse(login, blocks.get(next + 1), FingerParser::parse)));
            next += 2;
        }
        return users;
    }

    /**
     * The client connections of {@code --who}: its {@code Toolset} rows, without a login time.
     *
     * @throws ToolErrorException with the node set, for a failed run or an unknown table
     */
    public List<LoginEntry> logins() {
        CliResult result = start(selection -> runner.runClientOption(selection, username(),
            password(), ClientOption.WHO, server.cliTimeout(), guard));
        if (result.exitCode() != 0 || result.truncated()
            || CliOutputClassifier.isLoginFailure(result)) {
            throwFailure(result, true);
        }
        try {
            return WhoParser.parse(classifier.parse(result).outputLines());
        } catch (ToolErrorException e) {
            throw failed(e.error());
        }
    }

    private static CliSessionCommand command(String name, String login) {
        return CliSessionCommand.command(name).login(login).build();
    }

    private static <T> Block<T> parse(String login, List<String> block,
        BiFunction<String, List<String>, T> parser) {
        try {
            return new Block.Parsed<>(parser.apply(login, block));
        } catch (ToolErrorException e) {
            return new Block.Unavailable<>(e.error());
        }
    }

    private List<List<String>> session(List<CliSessionCommand> commands) {
        CliResult result = start(selection -> runner.runSession(selection, username(),
            password(), OPTIONS, commands, server.cliTimeout(), CliRunner.MAX_OUTPUT_BYTES,
            guard));
        if (result.exitCode() != 0 && !commandRefused(result.stdout(), commands)
            || result.truncated() || CliOutputClassifier.isLoginFailure(result)) {
            // the classifier's excerpt would show other users' blocks (e-mail, name; FR-011)
            throwFailure(result, false);
        }
        try {
            return CliSessionParser.STANDARD.blocks(result.stdout(), commands);
        } catch (ToolErrorException e) {
            throw failed(e.error());
        }
    }

    /**
     * The checks before a run, as in {@code CliSettingsRunner} and script mode: the client, the
     * credentials, the launch guard, then the pin; then {@code run}.
     */
    private CliResult start(Function<CliRunner.Selection, CliResult> run) {
        CliRunner.Selection selection = runner.select(server);
        runner.checkAvailable(selection);
        if (!credentials.complete()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.AUTH_FAILED,
                "No INUBIT credentials are configured for " + server.id()
                    + ", so StartCLI cannot log in",
                "The username or password variable for " + server.id() + " is not set",
                guard.fixCredentials()).withNode(server.id()));
        }
        try {
            runner.checkLaunch(selection);
            // before the guard: a refused address must neither wait for nor count as a login
            runner.checkPin(selection);
            return run.apply(selection);
        } catch (ToolErrorException e) {
            throw failed(e.error());
        }
    }

    private String username() {
        return credentials.username().orElseThrow().value();
    }

    private Secret password() {
        return credentials.password().orElseThrow().value();
    }

    /**
     * Throws the classifier's error, with its excerpt only if {@code withExcerpt}. The classifier
     * scrubs only registered secrets, so secret-named values in the excerpt are masked here as in
     * the parsers' excerpts ({@code UnknownOutput}, SC-005, T036). The output prints no n-OK
     * message, so the classifier never calls it a success.
     */
    private void throwFailure(CliResult result, boolean withExcerpt) {
        if (classifier.classify(result) instanceof CliOutcome.Failure failure) {
            ToolError error = failure.error();
            throw failed(new ToolError(error.code(), error.message(), error.likelyCause(),
                withExcerpt ? error.nextStep()
                    : error.nextStep().replace("See the excerpt; check", "Check"),
                error.node(),
                withExcerpt ? error.excerpt().map(SecretTextMasker::mask) : Optional.empty()));
        }
    }

    /** Whether the session's blocks split and one of them is a refused command (L-1). */
    private static boolean commandRefused(String stdout, List<CliSessionCommand> commands) {
        try {
            return CliSessionParser.STANDARD.blocks(stdout, commands).stream()
                .anyMatch(block -> !block.isEmpty() && block.getFirst().equals(EXECUTION_ERROR));
        } catch (ToolErrorException e) {
            return false;
        }
    }

    private ToolErrorException failed(ToolError error) {
        return new ToolErrorException(
            error.node().isPresent() ? error : error.withNode(server.id()));
    }

    @Override
    public String toString() {
        return "CliUserRunner[" + server.id() + ", timeout "
            + Durations.human(server.cliTimeout()) + "]";
    }
}
