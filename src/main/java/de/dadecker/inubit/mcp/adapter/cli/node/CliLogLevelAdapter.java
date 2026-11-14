package de.dadecker.inubit.mcp.adapter.cli.node;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.cli.CliCommand;
import de.dadecker.inubit.mcp.adapter.cli.CliOutcome;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFailures;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperRequest;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.node.LoggerName;
import de.dadecker.inubit.mcp.domain.model.node.TemporaryLevel;
import de.dadecker.inubit.mcp.domain.port.LogLevelPort;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.time.Duration;
import java.util.Objects;

/**
 * The temporary log levels of one 8.1/9.0 node (feature 024, FR-007, FR-042, research D-1, D-3,
 * D-11):
 *
 * <ul>
 *   <li>{@link #answeringNode}: one {@link NodeHelperRunner} run of the client helper's
 *       {@code ownNode} operation within the call deadline ({@code TIMEOUT} names
 *       {@code cliTimeout}); the operation has no frames of its own, so any frame after
 *       {@code node ok <name>} is {@code UNEXPECTED_RESPONSE}.
 *   <li>{@link #raise} and {@link #resetAll}: one StartCLI run each,
 *       {@link CliCommand#traceNodeLevel} for one logger or {@link CliCommand#traceReset},
 *       through {@link CliRunner#run} with the node's {@link CredentialGuard} (client choice of
 *       feature 017, pin check, password on stdin) and judged by
 *       {@link CliOutputClassifier#classifyTraceLevel}: a success needs StartCLI's confirmation.
 * </ul>
 *
 * <p>The lookup and a StartCLI run are separate logins; behind a load balancer they may reach
 * different nodes (D-3).
 */
public final class CliLogLevelAdapter implements LogLevelPort {

    private static final String BUDGET = "cliTimeout";
    private static final HelperFailures.Subject SUBJECT = new HelperFailures.Subject(
        "INUBIT client helper", "The lookup of the answering node",
        "Reset with reset_temporary_log_level; report the INUBIT version",
        "refused the name of its INUBIT node for the configured user",
        "The INUBIT user of this node lacks the right to read its node",
        "Give the user the right the Workbench's Cluster Manager needs (Configuration)");

    private final NodeHelperRunner helper;
    private final EffectiveNodeConfig server;
    private final NodeCredentials credentials;
    private final CredentialGuard guard;
    private final CliRunner runner;
    private final CliOutputClassifier classifier;
    private final NodeFramesClassifier frames;
    private final HelperFailures failures;

    /**
     * @param helper runs the client helper on the node
     * @param server the node StartCLI runs for
     * @param credentials the node's credentials for StartCLI
     * @param guard the node's guard of authenticated attempts
     * @param runner runs StartCLI
     * @param classifier the node's StartCLI classifier
     * @param scrubber masks the helper's texts
     */
    public CliLogLevelAdapter(NodeHelperRunner helper, EffectiveNodeConfig server,
        NodeCredentials credentials, CredentialGuard guard, CliRunner runner,
        CliOutputClassifier classifier, SecretScrubber scrubber) {
        this.helper = Objects.requireNonNull(helper, "helper");
        this.server = Objects.requireNonNull(server, "server");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.frames = new NodeFramesClassifier(scrubber, server.secretLabels(), SUBJECT);
        this.failures = new HelperFailures(scrubber, server.secretLabels(), SUBJECT);
    }

    @Override
    public String answeringNode(InubitVersion version, long deadlineNanos) {
        try (NodeHelperRunner.Run run = helper.run(Capability.LOG_LEVEL_WRITE, version,
            out -> new HelperRequest.OwnNode(), frames, BUDGET, deadlineNanos)) {
            if (!run.frames().isEmpty()) {
                throw new ToolErrorException(failures.unexpected("The INUBIT client helper wrote"
                    + " frames after the node's name; the own-node lookup has none"));
            }
            return run.inubitNode();
        }
    }

    /**
     * {@inheritDoc} StartCLI is chosen by {@link CliRunner} from the node's effective version
     * (feature 017), so {@code version} is not used for the run.
     */
    @Override
    public void raise(TemporaryLevel level, LoggerName logger, InubitVersion version,
        Duration timeout) {
        run(CliCommand.traceNodeLevel(level, logger), timeout);
    }

    /**
     * {@inheritDoc} StartCLI is chosen by {@link CliRunner} from the node's effective version
     * (feature 017), so {@code version} is not used for the run.
     */
    @Override
    public void resetAll(InubitVersion version, Duration timeout) {
        run(CliCommand.traceReset(), timeout);
    }

    private void run(CliCommand command, Duration timeout) {
        runner.checkAvailable(server);
        if (!credentials.complete()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.AUTH_FAILED,
                "No INUBIT credentials are configured for " + server.id()
                    + ", so StartCLI cannot log in",
                "The username or password variable for " + server.id() + " is not set",
                guard.fixCredentials()).withNode(server.id()));
        }
        CliResult result = runner.run(server, credentials.username().orElseThrow().value(),
            credentials.password().orElseThrow().value(), command, timeout, BUDGET, guard);
        if (classifier.classifyTraceLevel(result) instanceof CliOutcome.Failure failure) {
            ToolError error = failure.error();
            throw new ToolErrorException(error.node().isPresent() ? error
                : error.withNode(server.id()));
        }
    }
}
