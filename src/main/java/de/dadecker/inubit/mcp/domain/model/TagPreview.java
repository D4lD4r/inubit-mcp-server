package de.dadecker.inubit.mcp.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The preview of {@code tag_artifacts} under server-side confirmation (feature 004, research
 * D-2, D-16): nothing was sent; the tag is set only when the call is repeated with
 * {@code confirmationCode} before {@code expiresAt}.
 *
 * @param workflows the number of technical workflows of the diagram groups (their head versions
 *                  get the tag, with the modules they use)
 */
public record TagPreview(NodeId node, String owner, String tag, List<String> diagramGroups,
    int workflows, String confirmationCode, Instant expiresAt, String message) {

    public TagPreview {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(tag, "tag");
        diagramGroups = List.copyOf(diagramGroups);
        Objects.requireNonNull(confirmationCode, "confirmationCode");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(message, "message");
    }
}
