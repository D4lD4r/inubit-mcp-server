package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.ConnectorFlags;
import de.dadecker.inubit.mcp.domain.model.InventoryItem;
import de.dadecker.inubit.mcp.domain.model.ModuleRef;
import de.dadecker.inubit.mcp.domain.model.VersionEntry;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The inventory sources of one INUBIT server (US3, research R-11): diagrams via REST, the module
 * index and version histories via CLI exports. Failures are thrown as
 * {@link de.dadecker.inubit.mcp.domain.model.ToolErrorException} with the server id. The
 * methods are thread-safe; caching is the caller's business ({@code InventoryCache}).
 *
 * <p>Texts are returned as INUBIT sends them; the application layer bounds them.
 */
public interface InventoryPort {

    /** All diagrams owned by {@code owner} (REST {@code /model/models?user=<owner>}). */
    List<InventoryItem> listDiagrams(String owner);

    /**
     * The diagram {@code name} with its nodes (REST {@code /model/modelByName/<name>}).
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException {@code NOT_FOUND} if
     *     INUBIT does not know the diagram
     */
    DiagramDetail diagramDetail(String owner, String name);

    /** Active flag, check-in comment and owner of the head version (REST export, in memory). */
    DiagramMetadata diagramMetadata(String name);

    /**
     * The version history of the workflows of {@code group} and the modules they use (CLI export
     * with history). Values the CLI quoting rule does not allow are {@code UNEXPECTED_RESPONSE}
     * without launching StartCLI; no CLI home or Windows is {@code CLI_UNAVAILABLE}.
     */
    VersionHistory versionHistory(String owner, String type, String group);

    /**
     * The module index of {@code owner} (CLI module export; about 10–15 s on the recorded
     * development node). Which workflows use a module is not part of it; the application layer
     * derives that from the nodes of the technical workflows ({@link #diagramDetail}).
     */
    List<ModuleEntry> listModules(String owner);

    /**
     * @param type    the diagram type of the model, if the response states it
     * @param version the {@code version} attribute (e.g. {@code head})
     * @param modules the {@code Node} elements in document order
     */
    record DiagramDetail(String name, Optional<String> type, Optional<String> version,
        List<ModuleRef> modules) {

        public DiagramDetail {
            Objects.requireNonNull(name, "name");
            type = type == null ? Optional.empty() : type;
            version = version == null ? Optional.empty() : version;
            modules = List.copyOf(modules);
        }
    }

    /** From {@code workflow/workflow.xml} of the export; empty values if INUBIT omits them. */
    record DiagramMetadata(Optional<Boolean> active, Optional<String> checkinComment,
        Optional<String> owner) {

        public static final DiagramMetadata EMPTY =
            new DiagramMetadata(Optional.empty(), Optional.empty(), Optional.empty());

        public DiagramMetadata {
            active = active == null ? Optional.empty() : active;
            checkinComment = checkinComment == null ? Optional.empty() : checkinComment;
            owner = owner == null ? Optional.empty() : owner;
        }
    }

    /**
     * The histories of one export, by workflow and module name; each list newest first.
     */
    record VersionHistory(Map<String, List<VersionEntry>> workflows,
        Map<String, List<VersionEntry>> modules) {

        public VersionHistory {
            workflows = Map.copyOf(workflows);
            modules = Map.copyOf(modules);
        }
    }

    /**
     * One entry of the module index: the list item (without usage) plus the detail-only fields.
     *
     * @param connectorWorkflow {@code WorkflowName} of the export: INUBIT 8.1 states it only for
     *     connector modules (the workflow the connector is bound to), so it is one source of the
     *     module's usage, not the usage (finding F1)
     */
    record ModuleEntry(InventoryItem item, Optional<String> checkinComment,
        Optional<String> userComment, ConnectorFlags connector,
        Optional<String> connectorWorkflow) {

        public ModuleEntry {
            Objects.requireNonNull(item, "item");
            checkinComment = checkinComment == null ? Optional.empty() : checkinComment;
            userComment = userComment == null ? Optional.empty() : userComment;
            Objects.requireNonNull(connector, "connector");
            connectorWorkflow = connectorWorkflow == null ? Optional.empty() : connectorWorkflow;
        }
    }
}
