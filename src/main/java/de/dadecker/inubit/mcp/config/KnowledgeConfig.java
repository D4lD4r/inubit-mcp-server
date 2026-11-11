package de.dadecker.inubit.mcp.config;

import java.nio.file.Path;
import java.util.Objects;

/**
 * {@code knowledge} block of a profile (feature 019, research D-1,
 * contracts/configuration-delta.md): the directory of the local knowledge and whether the
 * assistant may record notes ({@code record_knowledge} is offered). {@link ConfigLoader} fills
 * the defaults ({@code ~/.inubit-mcp/<profile>/knowledge}, recording on) and refuses a
 * non-boolean {@code recording}; {@link ConfigValidator} checks the directory.
 */
public record KnowledgeConfig(Path directory, boolean recording) {

    public KnowledgeConfig {
        Objects.requireNonNull(directory, "directory");
    }
}
