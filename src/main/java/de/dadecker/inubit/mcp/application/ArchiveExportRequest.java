package de.dadecker.inubit.mcp.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The input of one {@code export_archives} call (feature 015, data-model.md →
 * ArchiveExportRequest), as the tool received it after the input schema. Feature 022 (research
 * D-21) adds the {@code content} forms {@code ownerWithoutGroups} and {@code metadata}.
 *
 * @param target                      the id of one group (its first node is used) or one node
 * @param owner                       the owner; default {@code inventory.owner} of the node; not
 *                                    used by {@code metadata}
 * @param content                     what is exported
 * @param diagramGroup                the diagram group whose technical workflows are archived;
 *                                    {@code null} unless {@code content} is {@code workflows}
 * @param workflows                   only these workflows (case-sensitive); empty for all
 * @param excludeGroups               the diagram groups {@code ownerWithoutGroups} leaves out;
 *                                    empty for the other forms
 * @param metadataNames               the registry entries {@code metadata} exports; empty for the
 *                                    whole registry and for the other forms
 * @param targetFolder                the folder for the archives, as given
 * @param acknowledgeClearTextSecrets whether the caller acknowledged the clear-text secrets
 * @param reason                      why the archives are exported, for the audit log
 * @param mcpClient                   the MCP client's {@code name/version}, if sent
 */
public record ArchiveExportRequest(String target, Optional<String> owner, Content content,
    String diagramGroup, List<String> workflows, List<String> excludeGroups,
    List<String> metadataNames, String targetFolder, boolean acknowledgeClearTextSecrets,
    Optional<String> reason, Optional<String> mcpClient) {

    /** The {@code content} input (research D-2: the tool's action key). */
    public enum Content {
        /** One archive per technical workflow of one diagram group (feature 015). */
        WORKFLOWS("workflows"),
        /** One archive of the owner's technical workflows without the excluded groups. */
        OWNER_WITHOUT_GROUPS("ownerWithoutGroups"),
        /** One archive of the metadata registry, whole or the named entries. */
        METADATA("metadata");

        private final String key;

        Content(String key) {
            this.key = key;
        }

        /** The value of the {@code content} input. */
        public String key() {
            return key;
        }

        /** @throws IllegalArgumentException for a value outside the input schema's enum */
        public static Content of(String key) {
            for (Content content : values()) {
                if (content.key.equals(key)) {
                    return content;
                }
            }
            throw new IllegalArgumentException("Unknown content " + key);
        }
    }

    /**
     * @throws IllegalArgumentException for an input the input schema does not allow with
     *     {@code content}
     */
    public ArchiveExportRequest {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(content, "content");
        workflows = List.copyOf(workflows);
        excludeGroups = List.copyOf(excludeGroups);
        metadataNames = List.copyOf(metadataNames);
        if (content == Content.WORKFLOWS) {
            Objects.requireNonNull(diagramGroup, "diagramGroup");
        }
        if (content != Content.WORKFLOWS && (diagramGroup != null || !workflows.isEmpty())
            || content != Content.OWNER_WITHOUT_GROUPS && !excludeGroups.isEmpty()
            || content != Content.METADATA && !metadataNames.isEmpty()
            || content == Content.METADATA && owner.isPresent()) {
            throw new IllegalArgumentException("An input not allowed with content "
                + content.key());
        }
        Objects.requireNonNull(targetFolder, "targetFolder");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(mcpClient, "mcpClient");
    }

    /** A {@code workflows} call (feature 015). */
    public ArchiveExportRequest(String target, Optional<String> owner, String diagramGroup,
        List<String> workflows, String targetFolder, boolean acknowledgeClearTextSecrets,
        Optional<String> reason, Optional<String> mcpClient) {
        this(target, owner, Content.WORKFLOWS, diagramGroup, workflows, List.of(), List.of(),
            targetFolder, acknowledgeClearTextSecrets, reason, mcpClient);
    }
}
