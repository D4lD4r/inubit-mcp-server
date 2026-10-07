package de.dadecker.inubit.mcp.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The preview of a deployment (feature 005, FR-011, research D-6, data-model.md →
 * DeploymentPreview): the release found on the source, one plan per target node and — only if
 * every plan is executable — the confirmation code bound to the release and every node's state.
 *
 * @param diagramGroups the diagram groups that carry the tag on the source
 * @param olderThanHead tagged versions older than head on the source (the tagged one is
 *                      deployed)
 * @param notes         facts for the reader (e.g. that system diagrams are never read)
 * @param previewState  {@code release=<fingerprint>;groups=<list>;<node>=<fingerprint>…}
 * @param instruction   what the assistant has to do next
 */
public record DeploymentPreview(UUID auditId, GroupId target, GroupId source, String tag,
    String owner, DeployMode mode, List<String> diagramGroups, List<String> olderThanHead,
    List<NodePlan> plans, List<String> notes, String previewState,
    Optional<String> confirmationCode, Optional<Instant> expiresAt, String instruction) {

    public DeploymentPreview {
        Objects.requireNonNull(auditId, "auditId");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(mode, "mode");
        diagramGroups = List.copyOf(diagramGroups);
        olderThanHead = List.copyOf(olderThanHead);
        plans = List.copyOf(plans);
        notes = List.copyOf(notes);
        Objects.requireNonNull(previewState, "previewState");
        confirmationCode = confirmationCode == null ? Optional.empty() : confirmationCode;
        expiresAt = expiresAt == null ? Optional.empty() : expiresAt;
        Objects.requireNonNull(instruction, "instruction");
    }

    /** True if every node plan is executable (only then there is a code). */
    public boolean executable() {
        return plans.stream().allMatch(NodePlan::executable);
    }

    /** Never the code. */
    @Override
    public String toString() {
        return "DeploymentPreview[" + auditId + ", " + tag + " " + source + " → " + target
            + ", " + plans.size() + " node(s), executable " + executable() + "]";
    }
}
