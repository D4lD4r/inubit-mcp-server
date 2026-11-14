package de.dadecker.inubit.mcp.adapter;

import de.dadecker.inubit.mcp.domain.model.CliSettingsSnapshot;
import de.dadecker.inubit.mcp.domain.model.ConfigurationView;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.LicenseFacts;
import de.dadecker.inubit.mcp.domain.model.ListedUser;
import de.dadecker.inubit.mcp.domain.model.LoginEntry;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.UserFacts;
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
import de.dadecker.inubit.mcp.domain.port.ServerSettingsPort;
import de.dadecker.inubit.mcp.domain.port.TagPort;
import de.dadecker.inubit.mcp.domain.port.UserDirectoryPort;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The version-specific ports of one server: US2 (REST), US3 (REST and CLI exports), US4 (CLI),
 * feature 003 (CLI exports), feature 004 (CLI imports and tags), feature 018 (REST partner
 * management), feature 020 (queue-entry content over the StartCLI client), feature 021 (server
 * log downloads), feature 022 (server settings, US1, and the user directory, US2, each over
 * REST and StartCLI) and feature 024 (the node tools). Built by a
 * {@link PortSetRegistry.Factory} chosen for the node's effective version (feature 009 research
 * D-16); the monitoring port is not part of it.
 */
public record PortSet(ProcessQueryPort processes, LogPort logs, InventoryPort inventory,
    ProcessControlPort processControl, ArtifactPort artifacts, ImportPort imports,
    TagPort tags, PartnerManagementPort partners, QueueEntryContentPort queueEntryContent,
    ServerLogPort serverLogs, ServerSettingsPort serverSettings,
    UserDirectoryPort userDirectory, ClusterNodePort clusterNodes, IoLogPort ioLog,
    NodeConfigPort nodeConfig, LogLevelPort logLevel) {

    /**
     * The queue-entry port of port sets without a queue-entry adapter (before feature 020): it
     * refuses as {@link RefusingPorts} refuses, {@code UNSUPPORTED_VERSION} for the entry's node
     * without contact (research D-17).
     */
    static final QueueEntryContentPort NO_QUEUE_ENTRIES = (ref, version, parts) ->
        new RefusingPorts(ref.node()).read(ref, version, parts);

    /**
     * The server-log port of port sets without a server-log adapter (before feature 021): a
     * download fails with {@code CLI_UNAVAILABLE} (research D-19).
     */
    static final ServerLogPort NO_SERVER_LOGS = (kind, version, deadlineNanos) -> {
        throw new ToolErrorException(ToolError.of(ErrorCode.CLI_UNAVAILABLE,
            "Server log downloads are not available on this adapter line",
            "This port set has no server-log adapter",
            "Use a node with an INUBIT 8.1 CLI installation (cliHome)"));
    };

    /** The settings port of port sets without a settings adapter (before feature 022). */
    private static final ServerSettingsPort NO_SERVER_SETTINGS = new ServerSettingsPort() {
        @Override
        public LicenseFacts license() {
            throw noServerSettings();
        }

        @Override
        public ConfigurationView configuration(Optional<String> section, int maxValues,
            int maxChars) {
            throw noServerSettings();
        }

        @Override
        public CliSettingsSnapshot cliSettings(Set<String> parts) {
            throw noServerSettings();
        }
    };

    /** The user-directory port of port sets without a user adapter (before feature 022, US2). */
    private static final UserDirectoryPort NO_USER_DIRECTORY = new UserDirectoryPort() {
        @Override
        public List<ListedUser> listUsers() {
            throw noUserDirectory();
        }

        @Override
        public List<UserFacts> readUsers(List<String> logins) {
            throw noUserDirectory();
        }

        @Override
        public List<LoginEntry> loggedIn() {
            throw noUserDirectory();
        }
    };

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
        Objects.requireNonNull(serverSettings, "serverSettings");
        Objects.requireNonNull(userDirectory, "userDirectory");
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
        ServerLogPort serverLogs, ServerSettingsPort serverSettings,
        UserDirectoryPort userDirectory) {
        this(processes, logs, inventory, processControl, artifacts, imports, tags, partners,
            queueEntryContent, serverLogs, serverSettings, userDirectory,
            NodeToolsUnavailable.INSTANCE, NodeToolsUnavailable.INSTANCE,
            NodeToolsUnavailable.INSTANCE, NodeToolsUnavailable.INSTANCE);
    }

    /**
     * The ports before feature 022, US2: a user-directory read fails with
     * {@code UNSUPPORTED_VERSION}, as the default of {@code Gateway#userDirectory()}, a node-tool
     * call as by the previous constructor. Used by port sets without a user adapter.
     */
    public PortSet(ProcessQueryPort processes, LogPort logs, InventoryPort inventory,
        ProcessControlPort processControl, ArtifactPort artifacts, ImportPort imports,
        TagPort tags, PartnerManagementPort partners, QueueEntryContentPort queueEntryContent,
        ServerLogPort serverLogs, ServerSettingsPort serverSettings) {
        this(processes, logs, inventory, processControl, artifacts, imports, tags, partners,
            queueEntryContent, serverLogs, serverSettings, NO_USER_DIRECTORY);
    }

    /**
     * The ports before feature 022: a settings read fails with {@code UNSUPPORTED_VERSION}, as
     * the default of {@code Gateway#serverSettings()}, a user-directory read as by the previous
     * constructor. Used by port sets without a settings adapter.
     */
    public PortSet(ProcessQueryPort processes, LogPort logs, InventoryPort inventory,
        ProcessControlPort processControl, ArtifactPort artifacts, ImportPort imports,
        TagPort tags, PartnerManagementPort partners, QueueEntryContentPort queueEntryContent,
        ServerLogPort serverLogs) {
        this(processes, logs, inventory, processControl, artifacts, imports, tags, partners,
            queueEntryContent, serverLogs, NO_SERVER_SETTINGS);
    }

    /**
     * The ports before feature 021: a server log download fails with {@code CLI_UNAVAILABLE}
     * (research D-19), a settings read as by the previous constructor. Used by port sets
     * without a server-log adapter.
     */
    public PortSet(ProcessQueryPort processes, LogPort logs, InventoryPort inventory,
        ProcessControlPort processControl, ArtifactPort artifacts, ImportPort imports,
        TagPort tags, PartnerManagementPort partners, QueueEntryContentPort queueEntryContent) {
        this(processes, logs, inventory, processControl, artifacts, imports, tags, partners,
            queueEntryContent, NO_SERVER_LOGS);
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
            NO_QUEUE_ENTRIES);
    }

    private static ToolErrorException noServerSettings() {
        return new ToolErrorException(ToolError.of(ErrorCode.UNSUPPORTED_VERSION,
            "Server settings are not available on this adapter line",
            "This port set has no server settings adapter",
            "Call list_nodes for what this node supports"));
    }

    private static ToolErrorException noUserDirectory() {
        return new ToolErrorException(ToolError.of(ErrorCode.UNSUPPORTED_VERSION,
            "User reads are not available on this adapter line",
            "This port set has no user directory adapter",
            "Call list_nodes for what this node supports"));
    }

    /** The node-tool ports of a port set without node-tool adapters (feature 024). */
    static final class NodeToolsUnavailable
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
