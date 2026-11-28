package de.dadecker.inubit.mcp.adapter.cli.v61;

import de.dadecker.inubit.mcp.adapter.cli.CliOutcome;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.domain.model.CliHealthSnapshot;
import de.dadecker.inubit.mcp.domain.model.ContactedException;
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
 * The health port of an INUBIT 6.1 node (feature 012, research D-6): one StartCLI session with
 * {@link V61HealthParser#COMMANDS} through {@link V61Connection}, read into a
 * {@link CliHealthSnapshot}. Readiness, threads and blocking queue are always missing, with the
 * reason of the {@link CliLineProfile} ({@link UnavailablePart#NOT_AVAILABLE_ON_6_1} on 6.1); the
 * profile also says whether {@code uname} reports the server's version. Feature 025 (FR-024)
 * runs 7.4 on this adapter with the 7.4 profile.
 *
 * <p>A run that ended with another exit code than 0, cut output or a login failure is
 * classified by {@link CliOutputClassifier} (e.g. {@code AUTH_FAILED}, {@code UNREACHABLE},
 * {@code TLS_ERROR}); these errors and those of the connection propagate with the node set.
 */
public final class V61HealthAdapter implements CliHealthPort {

    private final NodeId node;
    private final V61Connection connection;
    private final CliOutputClassifier classifier;
    private final CliLineProfile profile;
    /** The parts the line does not offer, as in contracts/mcp-tools-delta.md. */
    private final List<UnavailablePart> notOffered;

    /** The health port of a 6.1 node ({@link CliLineProfile#V61}). */
    public V61HealthAdapter(NodeId node, V61Connection connection,
        CliOutputClassifier classifier) {
        this(node, connection, classifier, CliLineProfile.V61);
    }

    public V61HealthAdapter(NodeId node, V61Connection connection,
        CliOutputClassifier classifier, CliLineProfile profile) {
        this.node = Objects.requireNonNull(node, "node");
        this.connection = Objects.requireNonNull(connection, "connection");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.profile = Objects.requireNonNull(profile, "profile");
        String prefix = profile.unavailableReason() + ": INUBIT " + profile.release();
        this.notOffered = List.of(
            new UnavailablePart(UnavailablePart.READY, prefix + " has no readiness check"),
            new UnavailablePart(UnavailablePart.THREADS, prefix + " reports no thread figures"),
            new UnavailablePart(UnavailablePart.BLOCKING_QUEUE,
                prefix + " reports no blocking queue"));
    }

    /**
     * Runs the session bounded by the shorter of {@code budget} and the node's cliTimeout, with
     * the output bound of {@code --execCommand} runs (research D-10); without the launch guard,
     * as {@code get_health} reports the client in its own part (feature 017, research D-4).
     */
    @Override
    public CliHealthSnapshot snapshot(Duration budget) {
        CliResult result = connection.healthSession(V61HealthParser.COMMANDS, budget,
            CliRunner.MAX_OUTPUT_BYTES);
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
            throw failed(V61SessionParser.named(e.error(), profile));
        }
        return new CliHealthSnapshot(health.alive(), Optional.of(health.version()),
            profile.unameReportsServerVersion(), Optional.of(health.maintenance()),
            Optional.of(health.memory()), Optional.of(health.counts()), notOffered);
    }

    /** An error of the returned session: after contact (feature 025, FR-030 (b)). */
    private ContactedException failed(ToolError error) {
        return new ContactedException(error.node().isPresent() ? error : error.withNode(node));
    }

    @Override
    public String toString() {
        return "V61HealthAdapter[" + node + "]";
    }
}
