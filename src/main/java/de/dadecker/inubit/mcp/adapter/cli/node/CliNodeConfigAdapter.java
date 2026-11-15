package de.dadecker.inubit.mcp.adapter.cli.node;

import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFailures;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperRequest;
import de.dadecker.inubit.mcp.config.SecretLabels;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigName;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigValues;
import de.dadecker.inubit.mcp.domain.port.NodeConfigPort;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.util.List;
import java.util.Objects;

/**
 * Allowlisted configuration values of the answering 8.1/9.0 node, read with the INUBIT client
 * helper's {@code nodeConfig} operation (feature 024, FR-030 … FR-032, research D-2, D-10, D-20):
 * one {@link NodeHelperRunner} run within the call deadline, whose {@code TIMEOUT} names
 * {@code cliTimeout}; the request's {@code names} are the given {@link NodeConfigName}s only, and
 * the frames are judged by {@link NodeFramesClassifier#nodeConfig}. The helper writes no file, so
 * the private directory is deleted before the result is returned.
 */
public final class CliNodeConfigAdapter implements NodeConfigPort {

    private static final String BUDGET = "cliTimeout";
    private static final HelperFailures.Subject SUBJECT = new HelperFailures.Subject(
        "INUBIT client helper", "get_node_config",
        "Use the Workbench's node configuration; report the INUBIT version",
        "refused the node configuration for the configured user",
        "The INUBIT user of this node lacks the right to read the node configuration",
        "Give the user the right the Workbench's node configuration needs (Configuration)");

    private final NodeHelperRunner runner;
    private final NodeFramesClassifier classifier;

    /**
     * @param runner runs the helper on the node
     * @param scrubber masks the helper's texts and the configuration values
     * @param labels the node's secret names
     */
    public CliNodeConfigAdapter(NodeHelperRunner runner, SecretScrubber scrubber,
        SecretLabels labels) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.classifier = new NodeFramesClassifier(scrubber, labels, SUBJECT);
    }

    @Override
    public NodeConfigValues values(List<NodeConfigName> names, InubitVersion version,
        long deadlineNanos) {
        Objects.requireNonNull(names, "names");
        try (NodeHelperRunner.Run run = runner.run(Capability.NODE_CONFIG_READ, version,
            out -> new HelperRequest.NodeConfig(names), classifier, BUDGET, deadlineNanos)) {
            return classifier.nodeConfig(run.request(), run.inubitNode(), run.frames());
        }
    }
}
