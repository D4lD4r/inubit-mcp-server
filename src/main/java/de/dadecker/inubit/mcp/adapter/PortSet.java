package de.dadecker.inubit.mcp.adapter;

import de.dadecker.inubit.mcp.domain.model.CliSettingsSnapshot;
import de.dadecker.inubit.mcp.domain.model.ConfigurationView;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.LicenseFacts;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import de.dadecker.inubit.mcp.domain.port.ImportPort;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.PartnerManagementPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.domain.port.QueueEntryContentPort;
import de.dadecker.inubit.mcp.domain.port.ServerLogPort;
import de.dadecker.inubit.mcp.domain.port.ServerSettingsPort;
import de.dadecker.inubit.mcp.domain.port.TagPort;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The version-specific ports of one server: US2 (REST), US3 (REST and CLI exports), US4 (CLI),
 * feature 003 (CLI exports), feature 004 (CLI imports and tags), feature 018 (REST partner
 * management), feature 020 (queue-entry content over the StartCLI client), feature 021 (server
 * log downloads) and feature 022 (server settings over REST and StartCLI). Built by a
 * {@link PortSetRegistry.Factory} chosen for the node's effective version (feature 009 research
 * D-16); the monitoring port is not part of it.
 */
public record PortSet(ProcessQueryPort processes, LogPort logs, InventoryPort inventory,
    ProcessControlPort processControl, ArtifactPort artifacts, ImportPort imports,
    TagPort tags, PartnerManagementPort partners, QueueEntryContentPort queueEntryContent,
    ServerLogPort serverLogs, ServerSettingsPort serverSettings) {

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
    }

    /**
     * The ports before feature 022: a settings read fails with {@code UNSUPPORTED_VERSION}, as
     * the default of {@code Gateway#serverSettings()}. Used by port sets without a settings
     * adapter.
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

    private static ToolErrorException noServerSettings() {
        return new ToolErrorException(ToolError.of(ErrorCode.UNSUPPORTED_VERSION,
            "Server settings are not available on this adapter line",
            "This port set has no server settings adapter",
            "Call list_nodes for what this node supports"));
    }
}
