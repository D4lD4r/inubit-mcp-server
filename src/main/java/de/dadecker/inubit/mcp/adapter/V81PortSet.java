package de.dadecker.inubit.mcp.adapter;

import de.dadecker.inubit.mcp.adapter.cli.CliConcurrencyLimiter;
import de.dadecker.inubit.mcp.adapter.cli.CliExportRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliImportRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliTagRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliTraceRunner;
import de.dadecker.inubit.mcp.adapter.cli.ProcessRun;
import de.dadecker.inubit.mcp.adapter.cli.admin.CliSettingsRunner;
import de.dadecker.inubit.mcp.adapter.cli.admin.CliUserRunner;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperInstallation;
import de.dadecker.inubit.mcp.adapter.cli.log.CliServerLogAdapter;
import de.dadecker.inubit.mcp.adapter.cli.log.CliWebServiceLogAdapter;
import de.dadecker.inubit.mcp.adapter.cli.node.CliClusterNodeAdapter;
import de.dadecker.inubit.mcp.adapter.cli.node.CliIoLogAdapter;
import de.dadecker.inubit.mcp.adapter.cli.node.CliLogLevelAdapter;
import de.dadecker.inubit.mcp.adapter.cli.node.CliNodeConfigAdapter;
import de.dadecker.inubit.mcp.adapter.cli.node.NodeHelperRunner;
import de.dadecker.inubit.mcp.adapter.cli.queue.CliQueueEntryAdapter;
import de.dadecker.inubit.mcp.adapter.cli.v81.V81ArtifactAdapter;
import de.dadecker.inubit.mcp.adapter.cli.v81.V81ImportAdapter;
import de.dadecker.inubit.mcp.adapter.cli.v81.V81ProcessControlAdapter;
import de.dadecker.inubit.mcp.adapter.cli.v81.V81TagAdapter;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81LogAdapter;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81PartnerManagementAdapter;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81ProcessQueryAdapter;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81SettingsAdapter;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81UserAdapter;
import de.dadecker.inubit.mcp.domain.port.ClusterNodePort;
import de.dadecker.inubit.mcp.domain.port.IoLogPort;
import de.dadecker.inubit.mcp.domain.port.LogLevelPort;
import de.dadecker.inubit.mcp.domain.port.NodeConfigPort;
import de.dadecker.inubit.mcp.domain.port.QueueEntryContentPort;
import de.dadecker.inubit.mcp.domain.port.ServerLogPort;

/**
 * The 8.1 port set: the REST adapters on the server's client and the StartCLI adapters, the
 * settings and the user directory of feature 022 included; with a helper host, the queue-entry
 * helper of feature 020 (research D-17) and the server-log download of feature 021 (research
 * D-19) and the cluster's node list, the I/O log, the node configuration and the temporary log
 * level of feature 024 (research D-24) too. The set without helper host has no node tools: their
 * ports fail with {@code CLI_UNAVAILABLE} ({@link PortSet}).
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
     * The cluster's node list, the I/O log and the node configuration (feature 024) run the
     * INUBIT client helper with all of them; the temporary log level runs it for the own-node
     * lookup and StartCLI as the trace download does.
     */
    static PortSet create(PortSetRegistry.NodeResources node, HelperInstallation.Host host,
        ProcessRun processRun, CliConcurrencyLimiter limiter) {
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
        NodeConfigPort nodeConfig = new CliNodeConfigAdapter(nodeHelper, node.scrubber(),
            node.server().secretLabels());
        LogLevelPort logLevel = new CliLogLevelAdapter(nodeHelper, node.server(),
            node.credentials(), node.guard(), node.cliRunner(), classifier(node), node.scrubber());
        PortSet ports = create(node, queueEntries, serverLogs);
        return new PortSet(ports.processes(), ports.logs(), ports.inventory(),
            ports.processControl(), ports.artifacts(), ports.imports(), ports.tags(),
            ports.partners(), queueEntries, serverLogs, ports.serverSettings(),
            ports.userDirectory(), clusterNodes, ioLog, nodeConfig, logLevel);
    }

    /**
     * Without queue-entry helper: queue-entry content is refused as by {@link RefusingPorts}, a
     * server log download fails with {@code CLI_UNAVAILABLE} (as in {@link PortSet}'s
     * compatibility constructors).
     */
    static PortSet create(PortSetRegistry.NodeResources node) {
        return create(node, PortSet.NO_QUEUE_ENTRIES, PortSet.NO_SERVER_LOGS);
    }

    /**
     * Every port but the node tools in one step. The settings read the REST parts on the server's
     * client and the StartCLI parts in one session of a {@link CliSettingsRunner} (feature 022,
     * research D-7); the user directory lists the logins on the server's client and reads the
     * rest with a {@link CliUserRunner} (feature 022, research D-20).
     */
    private static PortSet create(PortSetRegistry.NodeResources node,
        QueueEntryContentPort queueEntries, ServerLogPort serverLogs) {
        V81InventoryAdapter inventory = new V81InventoryAdapter(node.server().id(), node.client(),
            new CliExportRunner(node.server(), node.credentials(), node.guard(), node.cliRunner(),
                classifier(node)));
        CliUserRunner users = new CliUserRunner(node.server(), node.credentials(), node.guard(),
            node.cliRunner(), classifier(node));
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
            new V81PartnerManagementAdapter(node.server().id(), node.client()),
            queueEntries,
            serverLogs,
            new V81SettingsAdapter(node.server().id(), node.client(),
                new CliSettingsRunner(node.server(), node.credentials(), node.guard(),
                    node.cliRunner(), classifier(node))::read),
            new V81UserAdapter(node.server().id(), node.client(), users::read, users::logins));
    }

    private static CliOutputClassifier classifier(PortSetRegistry.NodeResources node) {
        return new CliOutputClassifier(node.scrubber(), node.server().secretLabels(),
            node.cliRunner().windows());
    }
}
