package de.dadecker.inubit.mcp.adapter.cli.v61;

import de.dadecker.inubit.mcp.adapter.cli.CliOutcome;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.domain.model.CliHealthSnapshot;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.UnavailablePart;
import de.dadecker.inubit.mcp.domain.port.CliHealthPort;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The health port of an INUBIT 6.1 node (feature 011, research D-6): one StartCLI session with
 * {@link V61HealthParser#COMMANDS} through {@link V61Connection}, read into a
 * {@link CliHealthSnapshot}. Readiness, threads and blocking queue are always missing
 * ({@link UnavailablePart#NOT_AVAILABLE_ON_6_1}).
 *
 * <p>A run that ended with another exit code than 0, cut output or a login failure is
 * classified by {@link CliOutputClassifier} (e.g. {@code AUTH_FAILED}, {@code UNREACHABLE},
 * {@code TLS_ERROR}); these errors and those of the connection propagate with the node set.
 */
public final class V61HealthAdapter implements CliHealthPort {

    /**
     * Whether {@code uname} reports the server's version rather than the StartCLI client's
     * (FR-006, research D-6). Decided by the recordings (T037); until they confirm the server
     * version, the version counts as the client's.
     */
    static final boolean UNAME_REPORTS_SERVER_VERSION = false;

    /** The parts INUBIT 6.1 does not offer, as in contracts/mcp-tools-delta.md. */
    private static final List<UnavailablePart> NOT_ON_6_1 = List.of(
        new UnavailablePart(UnavailablePart.READY,
            UnavailablePart.NOT_AVAILABLE_ON_6_1 + ": INUBIT 6.1 has no readiness check"),
        new UnavailablePart(UnavailablePart.THREADS,
            UnavailablePart.NOT_AVAILABLE_ON_6_1 + ": INUBIT 6.1 reports no thread figures"),
        new UnavailablePart(UnavailablePart.BLOCKING_QUEUE,
            UnavailablePart.NOT_AVAILABLE_ON_6_1 + ": INUBIT 6.1 reports no blocking queue"));

    private final NodeId node;
    private final V61Connection connection;
    private final CliOutputClassifier classifier;

    public V61HealthAdapter(NodeId node, V61Connection connection,
        CliOutputClassifier classifier) {
        this.node = Objects.requireNonNull(node, "node");
        this.connection = Objects.requireNonNull(connection, "connection");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
    }

    /** Runs the session bounded by the shorter of {@code budget} and the node's cliTimeout. */
    @Override
    public CliHealthSnapshot snapshot(Duration budget) {
        CliResult result = connection.session(V61HealthParser.COMMANDS, budget);
        if (result.exitCode() != 0 || result.truncated()
            || CliOutputClassifier.isLoginFailure(result)) {
            // the session prints no n-OK message, so the classifier never calls this a success
            if (classifier.classify(result) instanceof CliOutcome.Failure failure) {
                throw failed(failure.error());
            }
        }
        V61HealthParser.Health health;
        try {
            health = V61HealthParser.parse(result.stdout());
        } catch (ToolErrorException e) {
            throw failed(e.error());
        }
        return new CliHealthSnapshot(health.alive(), Optional.of(health.version()),
            UNAME_REPORTS_SERVER_VERSION, Optional.of(health.maintenance()),
            Optional.of(health.memory()), Optional.of(health.counts()), NOT_ON_6_1);
    }

    private ToolErrorException failed(ToolError error) {
        return new ToolErrorException(error.node().isPresent() ? error : error.withNode(node));
    }

    @Override
    public String toString() {
        return "V61HealthAdapter[" + node + "]";
    }
}
