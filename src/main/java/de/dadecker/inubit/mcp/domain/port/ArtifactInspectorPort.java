package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.WorkflowGraph;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads workspace files for the structure checks of {@code check_artifacts} (feature 003,
 * research D-13); version-specific, so the checks themselves stay in the application layer.
 * Failures are thrown as {@link de.dadecker.inubit.mcp.domain.model.ToolErrorException}.
 */
public interface ArtifactInspectorPort {

    /**
     * The structure of the workflow file {@code file} (absolute, inside the workspace).
     *
     * @throws IllegalArgumentException if it is not a well-formed workflow document
     */
    WorkflowGraph workflow(Path file);

    /**
     * The names of the derived values of {@code file} that do not match its content (research
     * D-4): for a {@code module.xml} the {@code <property>MD5} values of embedded documents, for
     * a repository file {@code contentMD5}/{@code contentSize} of its {@code .meta} record.
     * Empty if all match or the file has none.
     */
    List<String> derivedValueMismatches(Path root, Path file);
}
