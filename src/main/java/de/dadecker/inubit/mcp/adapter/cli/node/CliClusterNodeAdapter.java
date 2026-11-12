package de.dadecker.inubit.mcp.adapter.cli.node;

import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFailures;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperRequest;
import de.dadecker.inubit.mcp.config.SecretLabels;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.node.ClusterNodes;
import de.dadecker.inubit.mcp.domain.port.ClusterNodePort;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.util.Objects;
import java.util.Optional;

/**
 * The cluster members one 8.1/9.0 node knows, read with the INUBIT client helper's
 * {@code clusterNodes} operation (feature 024, FR-010, FR-011, research D-2, D-7, D-20): one
 * {@link NodeHelperRunner} run within the call deadline, whose {@code TIMEOUT} names
 * {@code cliTimeout}; the frames are judged by {@link NodeFramesClassifier#clusterNodes}. The
 * helper writes no file, so the private directory is deleted before the result is returned.
 */
public final class CliClusterNodeAdapter implements ClusterNodePort {

    private static final String BUDGET = "cliTimeout";
    private static final HelperFailures.Subject SUBJECT = new HelperFailures.Subject(
        "INUBIT client helper", "list_cluster_nodes",
        "Use the Workbench's Cluster Manager; report the INUBIT version",
        "refused the cluster's node list for the configured user",
        "The INUBIT user of this node lacks the right to read the cluster's nodes",
        "Give the user the right the Workbench's Cluster Manager needs (Configuration)");

    private final NodeHelperRunner runner;
    private final NodeFramesClassifier classifier;

    /**
     * @param runner runs the helper on the node
     * @param scrubber masks the helper's texts and the detail values
     * @param labels the node's secret names
     */
    public CliClusterNodeAdapter(NodeHelperRunner runner, SecretScrubber scrubber,
        SecretLabels labels) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.classifier = new NodeFramesClassifier(scrubber, labels, SUBJECT);
    }

    @Override
    public ClusterNodes nodes(Optional<String> details, InubitVersion version,
        long deadlineNanos) {
        Objects.requireNonNull(details, "details");
        try (NodeHelperRunner.Run run = runner.run(version,
            out -> new HelperRequest.ClusterNodes(details), classifier, BUDGET, deadlineNanos)) {
            return classifier.clusterNodes(run.request(), run.inubitNode(), run.frames());
        }
    }
}
