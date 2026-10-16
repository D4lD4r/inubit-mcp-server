package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;
import java.util.Optional;

/**
 * One workspace repository file to send in a repository import (research D-7, D-8,
 * data-model.md).
 *
 * @param path     the full repository path, e.g. {@code /Root/jdoe/xsd/msg.xsd}
 * @param content  the file content as in the workspace (a restore: as in the backup)
 * @param metadata the metadata to send: the normalized XML of the {@code .meta} record for a
 *                 MODIFIED file, the node's metadata for an EXISTING file, or the backup's for
 *                 a restore (research D-9); empty for a file the node does not hold, which gets
 *                 synthesized metadata
 */
public record RepositoryUpload(String path, byte[] content, Optional<String> metadata) {

    public RepositoryUpload {
        Objects.requireNonNull(path, "path");
        content = Objects.requireNonNull(content, "content").clone();
        Objects.requireNonNull(metadata, "metadata");
    }

    @Override
    public byte[] content() {
        return content.clone();
    }

    /** The path and size; never the content. */
    @Override
    public String toString() {
        return "RepositoryUpload[" + path + ", " + content.length + " bytes]";
    }
}
