package de.dadecker.inubit.mcp.adapter;

import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import de.dadecker.inubit.mcp.domain.port.ImportPort;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.domain.port.QueueEntryContentPort;
import de.dadecker.inubit.mcp.domain.port.TagPort;
import java.util.Objects;

/**
 * The version-specific ports of one server: US2 (REST), US3 (REST and CLI exports), US4 (CLI),
 * feature 003 (CLI exports), feature 004 (CLI imports and tags) and feature 020 (queue-entry
 * content over the StartCLI client). Built by a {@link PortSetRegistry.Factory} chosen for the
 * node's effective version (feature 009 research D-16); the monitoring port is not part of it.
 */
public record PortSet(ProcessQueryPort processes, LogPort logs, InventoryPort inventory,
    ProcessControlPort processControl, ArtifactPort artifacts, ImportPort imports,
    TagPort tags, QueueEntryContentPort queueEntryContent) {

    public PortSet {
        Objects.requireNonNull(processes, "processes");
        Objects.requireNonNull(logs, "logs");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(processControl, "processControl");
        Objects.requireNonNull(artifacts, "artifacts");
        Objects.requireNonNull(imports, "imports");
        Objects.requireNonNull(tags, "tags");
        Objects.requireNonNull(queueEntryContent, "queueEntryContent");
    }

    /**
     * The ports before feature 020: queue-entry content is refused as {@link RefusingPorts}
     * refuses it, {@code UNSUPPORTED_VERSION} for the entry's node without contact (research
     * D-17). Used by the 6.1 port set and by port sets without a queue-entry adapter.
     */
    public PortSet(ProcessQueryPort processes, LogPort logs, InventoryPort inventory,
        ProcessControlPort processControl, ArtifactPort artifacts, ImportPort imports,
        TagPort tags) {
        this(processes, logs, inventory, processControl, artifacts, imports, tags,
            (ref, version, parts) -> new RefusingPorts(ref.node()).read(ref, version, parts));
    }
}
