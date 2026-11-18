package de.dadecker.inubit.mcp.adapter.cli.admin;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.cli.CliCommand;
import de.dadecker.inubit.mcp.adapter.cli.CliOutcome;
import de.dadecker.inubit.mcp.adapter.cli.CliOutput;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.domain.model.Durations;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.TraceLevel;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The StartCLI operations of {@code operate_server} on an INUBIT 8.1/9.0 node (feature 022, US4,
 * research D-22): each one script-mode run of its {@link CliCommand} form, bounded by the node's
 * {@code cliTimeout}, its answer read by its parser:
 *
 * <ul>
 *   <li>{@link #maintenanceState}: {@code maintenance -s} ({@link MaintenanceStateParser});
 *   <li>{@link #switchMaintenance}: {@code maintenance on|off [-eq true|false]}
 *       ({@link MaintenanceStateParser});
 *   <li>{@link #switchTrace}: {@code trace on|off}, {@link #setTraceLevel}: {@code trace -l
 *       <level>} ({@link TraceStateParser});
 *   <li>{@link #triggerMidnightTask}: {@code triggerMidnightTask}
 *       ({@link MidnightTaskAnswerParser});
 *   <li>{@link #deleteErrorProcesses}: {@code processdelete error}
 *       ({@link ProcessDeleteAnswerParser}).
 * </ul>
 *
 * <p>Every change takes a {@link WritePermit} and is started by {@link CliRunner} inside
 * {@code WriteLock.handOff}; a permit for another node starts nothing. None of the answers is recorded yet (T054, L-2, L-3): the parsers read assumed
 * forms and report any other answer as {@code UNEXPECTED_RESPONSE}, so a change whose answer is
 * unknown was sent but is not confirmed.
 *
 * <p>A failed run (credentials, pin, launch, timeout, an exit code other than 0, cut output, a
 * refused login, or an {@code n-NOK} message) is thrown with the node set and the classifier's
 * excerpt, secret-named values masked; an unknown answer is {@code UNEXPECTED_RESPONSE} with the
 * node set and the masked answer as excerpt.
 */
public final class CliOperationRunner {

    private final EffectiveNodeConfig server;
    private final NodeCredentials credentials;
    private final CredentialGuard guard;
    private final CliRunner runner;
    private final CliOutputClassifier classifier;

    public CliOperationRunner(EffectiveNodeConfig server, NodeCredentials credentials,
        CredentialGuard guard, CliRunner runner, CliOutputClassifier classifier) {
        this.server = Objects.requireNonNull(server, "server");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
    }

    /**
     * Whether maintenance mode is on, read with {@code maintenance -s}.
     *
     * @throws ToolErrorException see the class comment
     */
    public boolean maintenanceState() {
        return run(CliCommand.maintenanceState(), Optional.empty(), MaintenanceStateParser::parse);
    }

    /**
     * Switches maintenance mode.
     *
     * @param executeQueued {@code -eq}, only with {@code on}; {@code null} sends none
     * @return the state INUBIT answers with
     * @throws ToolErrorException {@code INVALID_INPUT} for {@code executeQueued} with {@code off}
     *     before anything is launched; otherwise see the class comment
     */
    public boolean switchMaintenance(boolean on, Boolean executeQueued, WritePermit permit) {
        return run(CliCommand.maintenanceSwitch(on, executeQueued), Optional.of(permit),
            MaintenanceStateParser::parse);
    }

    /**
     * Switches the trace.
     *
     * @return the state INUBIT answers with
     * @throws ToolErrorException see the class comment
     */
    public boolean switchTrace(boolean on, WritePermit permit) {
        return run(CliCommand.traceSwitch(on), Optional.of(permit), TraceStateParser::parseSwitch);
    }

    /**
     * Sets the trace level.
     *
     * @return the level INUBIT answers with
     * @throws ToolErrorException {@code INVALID_INPUT} for a missing level before anything is
     *     launched; otherwise see the class comment
     */
    public TraceLevel setTraceLevel(TraceLevel level, WritePermit permit) {
        return run(CliCommand.traceLevel(level), Optional.of(permit),
            TraceStateParser::parseLevel);
    }

    /**
     * Runs the midnight task now.
     *
     * @return INUBIT's answer
     * @throws ToolErrorException see the class comment
     */
    public String triggerMidnightTask(WritePermit permit) {
        return run(CliCommand.triggerMidnightTask(), Optional.of(permit),
            MidnightTaskAnswerParser::parse);
    }

    /**
     * Deletes the process instances in state ERROR.
     *
     * @return the number of processes INUBIT reports as deleted
     * @throws ToolErrorException see the class comment
     */
    public long deleteErrorProcesses(WritePermit permit) {
        return run(CliCommand.processDeleteError(), Optional.of(permit),
            ProcessDeleteAnswerParser::parse);
    }

    private <T> T run(CliCommand command, Optional<WritePermit> permit,
        Function<List<String>, T> parser) {
        permit.ifPresent(write -> write.requireFor(server.id()));
        runner.checkAvailable(server);
        if (!credentials.complete()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.AUTH_FAILED,
                "No INUBIT credentials are configured for " + server.id()
                    + ", so StartCLI cannot log in",
                "The username or password variable for " + server.id() + " is not set",
                guard.fixCredentials()).withNode(server.id()));
        }
        String username = credentials.username().orElseThrow().value();
        CliResult result;
        try {
            result = permit.isPresent()
                ? runner.run(server, username, credentials.password().orElseThrow().value(),
                    command, server.cliTimeout(), guard, permit.get())
                : runner.run(server, username, credentials.password().orElseThrow().value(),
                    command, server.cliTimeout(), guard);
        } catch (ToolErrorException e) {
            throw failed(e.error());
        }
        CliOutput output = classifier.parse(result);
        if ((result.exitCode() != 0 || result.truncated()
            || CliOutputClassifier.isLoginFailure(result) || !output.nokMessages().isEmpty())
            && classifier.classify(result) instanceof CliOutcome.Failure failure) {
            throw failed(failure.error());
        }
        try {
            return parser.apply(output.outputLines());
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
        return "CliOperationRunner[" + server.id() + ", timeout "
            + Durations.human(server.cliTimeout()) + "]";
    }
}
