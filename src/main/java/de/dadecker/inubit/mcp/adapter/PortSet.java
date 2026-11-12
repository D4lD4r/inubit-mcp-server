package de.dadecker.inubit.mcp.adapter;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.node.ClusterNodes;
import de.dadecker.inubit.mcp.domain.model.node.IoLogListing;
import de.dadecker.inubit.mcp.domain.model.node.IoMessageRef;
import de.dadecker.inubit.mcp.domain.model.node.LoggerName;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigName;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigValues;
import de.dadecker.inubit.mcp.domain.model.node.TemporaryLevel;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import de.dadecker.inubit.mcp.domain.port.ClusterNodePort;
import de.dadecker.inubit.mcp.domain.port.ImportPort;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.IoLogPort;
import de.dadecker.inubit.mcp.domain.port.LogLevelPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.NodeConfigPort;
import de.dadecker.inubit.mcp.domain.port.PartnerManagementPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.domain.port.QueueEntryContentPort;
import de.dadecker.inubit.mcp.domain.port.ServerLogPort;
import de.dadecker.inubit.mcp.domain.port.TagPort;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The version-specific ports of one server: US2 (REST), US3 (REST and CLI exports), US4 (CLI),
 * feature 003 (CLI exports), feature 004 (CLI imports and tags), feature 018 (REST partner
 * management), feature 020 (queue-entry content over the StartCLI client), feature 021 (server
 * log downloads) and feature 024 (the node tools). Built by a {@link PortSetRegistry.Factory}
 * chosen for the node's effective version (feature 009 research D-16); the monitoring port is not
 * part of it.
 */
public record PortSet(ProcessQueryPort processes, LogPort logs, InventoryPort inventory,
    ProcessControlPort processControl, ArtifactPort artifacts, ImportPort imports,
    TagPort tags, PartnerManagementPort partners, QueueEntryContentPort queueEntryContent,
    ServerLogPort serverLogs, ClusterNodePort clusterNodes, IoLogPort ioLog,
    NodeConfigPort nodeConfig, LogLevelPort logLevel) {

    public PortSet {
        Objects.requireNonNull(processes, "processes");
        Objects.requireNonNull(logs, "logs");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(processControl, "processControl");
        Objects.requireNonNull(artifacts, "artifacts");
        Objects.requireNonNull(imports, "imports");
        Objects.requireNonNull(tags, "tags");
        Objects.requireNonNull(partners, "partners");
        Objects.requireNonNull(queueEntryContent, "queueEntryContent");
        Objects.requireNonNull(serverLogs, "serverLogs");
        Objects.requireNonNull(clusterNodes, "clusterNodes");
        Objects.requireNonNull(ioLog, "ioLog");
        Objects.requireNonNull(nodeConfig, "nodeConfig");
        Objects.requireNonNull(logLevel, "logLevel");
    }

    /**
     * The ports before feature 024: every node-tool call fails with {@code CLI_UNAVAILABLE}
     * (research D-24). Used by port sets without node-tool adapters.
     */
    public PortSet(ProcessQueryPort processes, LogPort logs, InventoryPort inventory,
        ProcessControlPort processControl, ArtifactPort artifacts, ImportPort imports,
        TagPort tags, PartnerManagementPort partners, QueueEntryContentPort queueEntryContent,
        ServerLogPort serverLogs) {
        this(processes, logs, inventory, processControl, artifacts, imports, tags, partners,
            queueEntryContent, serverLogs, NodeToolsUnavailable.INSTANCE,
            NodeToolsUnavailable.INSTANCE, NodeToolsUnavailable.INSTANCE,
            NodeToolsUnavailable.INSTANCE);
    }

    /**
     * The ports before feature 021: a server log download fails with {@code CLI_UNAVAILABLE}
     * (research D-19), a node-tool call as by the previous constructor. Used by port sets
     * without a server-log adapter.
     */
    public PortSet(ProcessQueryPort processes, LogPort logs, InventoryPort inventory,
        ProcessControlPort processControl, ArtifactPort artifacts, ImportPort imports,
        TagPort tags, PartnerManagementPort partners, QueueEntryContentPort queueEntryContent) {
        this(processes, logs, inventory, processControl, artifacts, imports, tags, partners,
            queueEntryContent, (kind, version, deadlineNanos) -> {
                throw new ToolErrorException(ToolError.of(ErrorCode.CLI_UNAVAILABLE,
                    "Server log downloads are not available on this adapter line",
                    "This port set has no server-log adapter",
                    "Use a node with an INUBIT 8.1 CLI installation (cliHome)"));
            });
    }

    /**
     * The ports before feature 020: queue-entry content is refused as {@link RefusingPorts}
     * refuses it, {@code UNSUPPORTED_VERSION} for the entry's node without contact (research
     * D-17), a server log download as by the previous constructor. Used by port sets without a
     * queue-entry adapter.
     */
    public PortSet(ProcessQueryPort processes, LogPort logs, InventoryPort inventory,
        ProcessControlPort processControl, ArtifactPort artifacts, ImportPort imports,
        TagPort tags, PartnerManagementPort partners) {
        this(processes, logs, inventory, processControl, artifacts, imports, tags, partners,
            (ref, version, parts) -> new RefusingPorts(ref.node()).read(ref, version, parts));
    }

    /** The node-tool ports of a port set without node-tool adapters (feature 024). */
    private static final class NodeToolsUnavailable
        implements ClusterNodePort, IoLogPort, NodeConfigPort, LogLevelPort {

        static final NodeToolsUnavailable INSTANCE = new NodeToolsUnavailable();

        private static ToolErrorException unavailable() {
            return new ToolErrorException(ToolError.of(ErrorCode.CLI_UNAVAILABLE,
                "The node tools are not available on this adapter line",
                "This port set has no node-tool adapter",
                "Use a node with an INUBIT 8.1 CLI installation (cliHome)"));
        }

        @Override
        public ClusterNodes nodes(Optional<String> details, InubitVersion version,
            long deadlineNanos) {
            throw unavailable();
        }

        @Override
        public IoLogListing list(String processId, String workflow, InubitVersion version,
            long deadlineNanos) {
            throw unavailable();
        }

        @Override
        public IoLogRead read(String processId, String workflow, IoMessageRef ref,
            InubitVersion version, long deadlineNanos) {
            throw unavailable();
        }

        @Override
        public NodeConfigValues values(List<NodeConfigName> names, InubitVersion version,
            long deadlineNanos) {
            throw unavailable();
        }

        @Override
        public String answeringNode(InubitVersion version, long deadlineNanos) {
            throw unavailable();
        }

        @Override
        public void raise(TemporaryLevel level, LoggerName logger, InubitVersion version,
            Duration timeout) {
            throw unavailable();
        }

        @Override
        public void resetAll(InubitVersion version, Duration timeout) {
            throw unavailable();
        }
    }
}
