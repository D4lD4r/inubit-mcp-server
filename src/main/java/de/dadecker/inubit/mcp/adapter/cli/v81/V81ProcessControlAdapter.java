package de.dadecker.inubit.mcp.adapter.cli.v81;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.cli.CliCommand;
import de.dadecker.inubit.mcp.adapter.cli.CliOutcome;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import java.util.Objects;

/**
 * Restart and kill on INUBIT 8.1 through StartCLI (US4, research R-6, R-7; spikes S-3, S-4).
 *
 * <ul>
 *   <li>{@code processErrorStart <pid>} and {@code kill <pid>}, built by {@link CliCommand}: the
 *       id must be the Queue Manager id ({@code ^[1-9][0-9]{0,18}$}, the queueLog
 *       {@code workflowId}); anything else, e.g. a UUID {@code globalPId}, is
 *       {@code INVALID_INPUT} before anything is launched.
 *   <li>Every run goes through the server's {@link CredentialGuard} (shared with REST) with the
 *       server's {@code cliTimeout}; the password goes to stdin, never into the arguments.
 *   <li>The output is classified by {@link CliOutputClassifier}: exit code 0 with an
 *       {@code n-OK} message and no {@code n-NOK} is success (its text is returned); an unknown
 *       process is {@code NOT_FOUND}, a rejected login {@code AUTH_FAILED}; anything else is
 *       {@code UNEXPECTED_RESPONSE}, never a guessed success.
 *   <li>No CLI home, no JDK, no script or Windows is {@code CLI_UNAVAILABLE}; missing credentials
 *       {@code AUTH_FAILED}; both without launching StartCLI.
 * </ul>
 *
 * <p>The exact success texts of 8.1.17 are not recorded yet (fixtures {@code *_ok} are
 * synthetic until T115), so any {@code n-OK} text counts as success.
 */
public final class V81ProcessControlAdapter implements ProcessControlPort {

    private final EffectiveNodeConfig server;
    private final NodeCredentials credentials;
    private final CredentialGuard guard;
    private final CliRunner runner;
    private final CliOutputClassifier classifier;

    public V81ProcessControlAdapter(EffectiveNodeConfig server, NodeCredentials credentials,
        CredentialGuard guard, CliRunner runner, CliOutputClassifier classifier) {
        this.server = Objects.requireNonNull(server, "server");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
    }

    @Override
    public void checkAvailable() {
        runner.checkAvailable(server);
        if (!credentials.complete()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.AUTH_FAILED,
                "No INUBIT credentials are configured for " + server.id()
                    + ", so StartCLI cannot log in",
                "The username or password variable for " + server.id() + " is not set",
                guard.fixCredentials()).withNode(server.id()));
        }
    }

    @Override
    public String restart(String processId) {
        return run(CliCommand.processErrorStart(processId));
    }

    @Override
    public String kill(String processId) {
        return run(CliCommand.kill(processId));
    }

    private String run(CliCommand command) {
        checkAvailable();
        CliResult result = runner.run(server, credentials.username().orElseThrow().value(),
            credentials.password().orElseThrow().value(), command, server.cliTimeout(), guard);
        CliOutcome outcome = classifier.classify(result);
        if (outcome instanceof CliOutcome.Failure failure) {
            ToolError error = failure.error();
            throw new ToolErrorException(error.node().isPresent() ? error
                : error.withNode(server.id()));
        }
        return String.join(" / ", ((CliOutcome.Success) outcome).output().okMessages());
    }

    @Override
    public String toString() {
        return "V81ProcessControlAdapter[" + server.id() + "]";
    }
}
