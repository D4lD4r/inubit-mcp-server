package de.dadecker.inubit.mcp.adapter.cli.admin;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.cli.CliOutcome;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliSessionCommand;
import de.dadecker.inubit.mcp.adapter.cli.CliSessionParser;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.domain.model.CliClientOptions;
import de.dadecker.inubit.mcp.domain.model.CliSettingsSnapshot;
import de.dadecker.inubit.mcp.domain.model.DbConnectionTolerance;
import de.dadecker.inubit.mcp.domain.model.Durations;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.PrivilegedWorkflows;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.UnavailablePart;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The StartCLI parts of {@code get_server_settings} on an INUBIT 8.1/9.0 node (feature 022, US1,
 * research D-7): the commands of the asked parts run in one stdin session bounded by the node's
 * {@code cliTimeout}, and each block goes to its parser.
 *
 * <ul>
 *   <li>{@code threads} — {@value ThreadsParser#COMMAND};
 *   <li>{@code privilegedWorkflows} — {@value PrivilegedWorkflowsParser#PERCENTAGE_COMMAND} and
 *       {@value PrivilegedWorkflowsParser#LIST_COMMAND};
 *   <li>{@code dbConnectionTolerance} — {@value DbToleranceParser#COMMAND};
 *   <li>{@code serverTime} — {@value ServerDateParser#COMMAND}.
 * </ul>
 *
 * <p>A block its parser cannot read marks only its part unavailable (FR-013). A failure of the
 * whole session (credentials, the pin of a {@code tls.pinPerServer} stage, launch, timeout, an
 * exit code other than 0, cut output, a refused login, a missing "CLI login access" right, or
 * not one prompt per command) is thrown with the node set, as no block can be told apart then.
 * The blocks are split with {@link CliSessionParser#STANDARD}; the recorded prompt and echo form
 * follows with T025 (L-1).
 */
public final class CliSettingsRunner {

    static final CliSessionCommand THREADS =
        CliSessionCommand.command("threads").flag("showNumberOfWorkflowThreads").build();
    static final CliSessionCommand PRIVILEGED_PERCENTAGE =
        CliSessionCommand.command("privilegedWorkflows").flag("showThreadsPercentage").build();
    static final CliSessionCommand PRIVILEGED_LIST =
        CliSessionCommand.command("privilegedWorkflows").flag("showWorkflowList").build();
    static final CliSessionCommand DB_TOLERANCE =
        CliSessionCommand.command("dbconnectiontolerance").flag("show").build();
    static final CliSessionCommand DATE = CliSessionCommand.command("date").build();

    /** The commands of each part, in the order they are sent. */
    private static final Map<String, List<CliSessionCommand>> COMMANDS = Map.of(
        UnavailablePart.THREADS, List.of(THREADS),
        UnavailablePart.PRIVILEGED_WORKFLOWS, List.of(PRIVILEGED_PERCENTAGE, PRIVILEGED_LIST),
        UnavailablePart.DB_CONNECTION_TOLERANCE, List.of(DB_TOLERANCE),
        UnavailablePart.SERVER_TIME, List.of(DATE));
    private static final List<String> ORDER = List.of(UnavailablePart.THREADS,
        UnavailablePart.PRIVILEGED_WORKFLOWS, UnavailablePart.DB_CONNECTION_TOLERANCE,
        UnavailablePart.SERVER_TIME);

    /**
     * The options of an 8.1/9.0 client, as script mode passes them: the trust store by
     * {@code --trustStoreFilePath}, {@code --disableHostNameVerification} where configured.
     */
    private static final CliClientOptions OPTIONS =
        new CliClientOptions(true, true, true, Optional.empty());

    private final EffectiveNodeConfig server;
    private final NodeCredentials credentials;
    private final CredentialGuard guard;
    private final CliRunner runner;
    private final CliOutputClassifier classifier;

    public CliSettingsRunner(EffectiveNodeConfig server, NodeCredentials credentials,
        CredentialGuard guard, CliRunner runner, CliOutputClassifier classifier) {
        this.server = Objects.requireNonNull(server, "server");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
    }

    /**
     * Reads {@code parts} in one session; no part starts nothing.
     *
     * @param parts {@code threads}, {@code privilegedWorkflows}, {@code dbConnectionTolerance}
     *     and/or {@code serverTime} ({@link UnavailablePart} names)
     * @return each asked part, or the part in {@code unavailable} with its parser's error
     * @throws ToolErrorException with the node set, for a failure of the whole session (see the
     *     class comment)
     * @throws IllegalArgumentException for a part StartCLI does not read here; nothing is started
     */
    public CliSettingsSnapshot read(Set<String> parts) {
        if (!COMMANDS.keySet().containsAll(parts)) {
            throw new IllegalArgumentException("not a StartCLI settings part: " + parts);
        }
        List<CliSessionCommand> commands = new ArrayList<>();
        ORDER.stream().filter(parts::contains).forEach(part -> commands.addAll(COMMANDS.get(part)));
        if (commands.isEmpty()) {
            return new CliSettingsSnapshot(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), List.of());
        }
        List<List<String>> blocks = session(commands);
        List<UnavailablePart> unavailable = new ArrayList<>();
        Optional<Long> threads = parse(UnavailablePart.THREADS, parts, unavailable,
            () -> ThreadsParser.parse(block(blocks, commands, THREADS)));
        Optional<PrivilegedWorkflows> privileged = parse(UnavailablePart.PRIVILEGED_WORKFLOWS,
            parts, unavailable, () -> PrivilegedWorkflowsParser.parse(
                block(blocks, commands, PRIVILEGED_PERCENTAGE),
                block(blocks, commands, PRIVILEGED_LIST)));
        Optional<DbConnectionTolerance> tolerance = parse(UnavailablePart.DB_CONNECTION_TOLERANCE,
            parts, unavailable,
            () -> DbToleranceParser.parse(block(blocks, commands, DB_TOLERANCE)));
        Optional<String> serverTime = parse(UnavailablePart.SERVER_TIME, parts, unavailable,
            () -> ServerDateParser.parse(block(blocks, commands, DATE)));
        return new CliSettingsSnapshot(threads, privileged, tolerance, serverTime, unavailable);
    }

    private List<List<String>> session(List<CliSessionCommand> commands) {
        CliRunner.Selection selection = runner.select(server);
        runner.checkAvailable(selection);
        if (!credentials.complete()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.AUTH_FAILED,
                "No INUBIT credentials are configured for " + server.id()
                    + ", so StartCLI cannot log in",
                "The username or password variable for " + server.id() + " is not set",
                guard.fixCredentials()).withNode(server.id()));
        }
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
        if (result.exitCode() != 0 || result.truncated()
            || CliOutputClassifier.isLoginFailure(result)) {
            // the session prints no n-OK message, so the classifier never calls this a success
            if (classifier.classify(result) instanceof CliOutcome.Failure failure) {
                throw failed(failure.error());
            }
        }
        try {
            return CliSessionParser.STANDARD.blocks(result.stdout(), commands);
        } catch (ToolErrorException e) {
            throw failed(e.error());
        }
    }

    private static List<String> block(List<List<String>> blocks,
        List<CliSessionCommand> commands, CliSessionCommand command) {
        return blocks.get(commands.indexOf(command));
    }

    /** The value of {@code part} if it was asked for; its parser's error goes to unavailable. */
    private static <T> Optional<T> parse(String part, Set<String> parts,
        List<UnavailablePart> unavailable, Supplier<T> parser) {
        if (!parts.contains(part)) {
            return Optional.empty();
        }
        try {
            return Optional.of(parser.get());
        } catch (ToolErrorException e) {
            unavailable.add(UnavailablePart.of(part, e.error()));
            return Optional.empty();
        }
    }

    private ToolErrorException failed(ToolError error) {
        return new ToolErrorException(
            error.node().isPresent() ? error : error.withNode(server.id()));
    }

    @Override
    public String toString() {
        return "CliSettingsRunner[" + server.id() + ", timeout "
            + Durations.human(server.cliTimeout()) + "]";
    }
}
