package de.dadecker.inubit.mcp.adapter;

import de.dadecker.inubit.mcp.adapter.cli.CliConcurrencyLimiter;
import de.dadecker.inubit.mcp.adapter.cli.CliExportRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliImportRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliTagRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliTraceRunner;
import de.dadecker.inubit.mcp.adapter.cli.ProcessRun;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperInstallation;
import de.dadecker.inubit.mcp.adapter.cli.log.CliServerLogAdapter;
import de.dadecker.inubit.mcp.adapter.cli.log.CliWebServiceLogAdapter;
import de.dadecker.inubit.mcp.adapter.cli.node.CliClusterNodeAdapter;
import de.dadecker.inubit.mcp.adapter.cli.node.CliIoLogAdapter;
import de.dadecker.inubit.mcp.adapter.cli.node.NodeHelperRunner;
import de.dadecker.inubit.mcp.adapter.cli.queue.CliQueueEntryAdapter;
import de.dadecker.inubit.mcp.adapter.cli.v81.V81ArtifactAdapter;
import de.dadecker.inubit.mcp.adapter.cli.v81.V81ImportAdapter;
import de.dadecker.inubit.mcp.adapter.cli.v81.V81ProcessControlAdapter;
import de.dadecker.inubit.mcp.adapter.cli.v81.V81TagAdapter;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81LogAdapter;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81PartnerManagementAdapter;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81ProcessQueryAdapter;
import de.dadecker.inubit.mcp.domain.port.ClusterNodePort;
import de.dadecker.inubit.mcp.domain.port.IoLogPort;
import de.dadecker.inubit.mcp.domain.port.QueueEntryContentPort;
import de.dadecker.inubit.mcp.domain.port.ServerLogPort;

/**
 * The 8.1 port set: the REST adapters on the server's client and the StartCLI adapters; with a
 * helper host, the queue-entry helper of feature 020 (research D-17) and the server-log
 * download of feature 021 (research D-19) and the cluster's node list and the I/O log of feature
 * 024 (research D-24) too. The other node tools of feature 024 have no adapter yet, nor has the
 * set without helper host the node list or the I/O log: their ports fail with
 * {@code CLI_UNAVAILABLE} ({@link PortSet}).
 */
final class V81PortSet {

    private V81PortSet() {
    }

    /**
     * With the queue-entry helper (feature 020, research D-17): it reads with the node's held
     * credentials and credential guard, the factory's StartCLI runner, and the shared
     * {@code host}, {@code processRun} and StartCLI slot {@code limiter}. The server-log
     * download (feature 021, research D-15) runs with the same credentials, guard, runner and
     * {@code limiter}, the web service log with the same {@code host} and {@code processRun}.
     * The cluster's node list and the I/O log (feature 024) run the INUBIT client helper with
     * all of them.
     */
    static PortSet create(PortSetRegistry.NodeResources node, HelperInstallation.Host host,
        ProcessRun processRun, CliConcurrencyLimiter limiter) {
        PortSet ports = create(node);
        QueueEntryContentPort queueEntries = new CliQueueEntryAdapter(node.server(),
            node::credentials, node.guard(), node.cliRunner(), host, processRun, limiter,
            node.scrubber());
        ServerLogPort serverLogs = new CliServerLogAdapter(node.server(),
            new CliTraceRunner(node.server(), node.credentials(), node.guard(), node.cliRunner(),
                classifier(node)),
            new CliWebServiceLogAdapter(node.server(), node::credentials, node.guard(),
                node.cliRunner(), host, processRun, node.scrubber()),
            limiter);
        NodeHelperRunner nodeHelper = new NodeHelperRunner(node.server(), node::credentials,
            node.guard(), node.cliRunner(), host, processRun, limiter);
        ClusterNodePort clusterNodes = new CliClusterNodeAdapter(nodeHelper, node.scrubber(),
            node.server().secretLabels());
        IoLogPort ioLog = new CliIoLogAdapter(nodeHelper, node.scrubber(),
            node.server().secretLabels());
        return new PortSet(ports.processes(), ports.logs(), ports.inventory(),
            ports.processControl(), ports.artifacts(), ports.imports(), ports.tags(),
            ports.partners(), queueEntries, serverLogs, clusterNodes, ioLog,
            PortSet.NodeToolsUnavailable.INSTANCE, PortSet.NodeToolsUnavailable.INSTANCE);
    }

    /**
     * Without queue-entry helper: queue-entry content is refused as by {@link RefusingPorts}, a
     * server log download fails with {@code CLI_UNAVAILABLE} ({@link PortSet}'s compatibility
     * constructors).
     */
    static PortSet create(PortSetRegistry.NodeResources node) {
        V81InventoryAdapter inventory = new V81InventoryAdapter(node.server().id(), node.client(),
            new CliExportRunner(node.server(), node.credentials(), node.guard(), node.cliRunner(),
                classifier(node)));
        return new PortSet(
            new V81ProcessQueryAdapter(node.server().id(), node.client()),
            new V81LogAdapter(node.server().id(), node.client()),
            inventory,
            new V81ProcessControlAdapter(node.server(), node.credentials(), node.guard(),
                node.cliRunner(), classifier(node)),
            new V81ArtifactAdapter(new CliExportRunner(node.server(), node.credentials(),
                node.guard(), node.cliRunner(), classifier(node)), inventory::confirmCredentials),
            new V81ImportAdapter(new CliImportRunner(node.server(), node.credentials(),
                node.guard(), node.cliRunner(), classifier(node)), inventory::confirmCredentials),
            new V81TagAdapter(node.server().id(), new CliExportRunner(node.server(),
                node.credentials(), node.guard(), node.cliRunner(), classifier(node)),
                new CliTagRunner(node.server(), node.credentials(), node.guard(),
                    node.cliRunner(), classifier(node)), inventory::confirmCredentials),
            new V81PartnerManagementAdapter(node.server().id(), node.client()));
    }

    private static CliOutputClassifier classifier(PortSetRegistry.NodeResources node) {
        return new CliOutputClassifier(node.scrubber(), node.server().secretLabels(),
            node.cliRunner().windows());
    }
}
