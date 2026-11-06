package de.dadecker.inubit.mcp.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The input of one {@code export_archives} call (feature 015, data-model.md →
 * ArchiveExportRequest), as the tool received it after the input schema.
 *
 * @param target                      the id of one group (its first node is used) or one node
 * @param owner                       the owner; default {@code inventory.owner} of the node
 * @param diagramGroup                the diagram group whose technical workflows are archived
 * @param workflows                   only these workflows (case-sensitive); empty for all
 * @param targetFolder                the folder for the archives, as given
 * @param acknowledgeClearTextSecrets whether the caller acknowledged the clear-text secrets
 * @param reason                      why the archives are exported, for the audit log
 * @param mcpClient                   the MCP client's {@code name/version}, if sent
 */
public record ArchiveExportRequest(String target, Optional<String> owner, String diagramGroup,
    List<String> workflows, String targetFolder, boolean acknowledgeClearTextSecrets,
    Optional<String> reason, Optional<String> mcpClient) {

    public ArchiveExportRequest {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(diagramGroup, "diagramGroup");
        workflows = List.copyOf(workflows);
        Objects.requireNonNull(targetFolder, "targetFolder");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(mcpClient, "mcpClient");
    }
}
