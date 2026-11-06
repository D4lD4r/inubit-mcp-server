package de.dadecker.inubit.mcp.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * What the Workbench archive of one technical workflow holds (feature 015, data-model.md →
 * WorkflowArchivePlan, research R-5, R-6): names only, never content.
 *
 * @param workflow          the workflow name
 * @param modules           the modules of the archive: the module of every node at any depth and
 *                          every module bound to the workflow, found in the export, each once, in
 *                          export order
 * @param missingModules    the names of node modules the export does not contain (FR-005)
 * @param repositoryPaths   the repository paths ({@code /Root/<owner>/…}) in the archive, sorted
 *                          and each once
 * @param workflowConnector whether a node's module has the plug-in type of a Workflow Connector
 *                          (the called workflow is not part of the archive)
 */
public record WorkflowArchivePlan(String workflow, List<ModuleEntry> modules,
    List<String> missingModules, List<String> repositoryPaths, boolean workflowConnector) {

    public WorkflowArchivePlan {
        Objects.requireNonNull(workflow, "workflow");
        modules = List.copyOf(modules);
        missingModules = List.copyOf(missingModules);
        repositoryPaths = List.copyOf(new TreeSet<>(repositoryPaths));
    }

    /**
     * One module of the archive.
     *
     * @param name       the module name
     * @param pluginType the plug-in type ({@code ModuleGroupName} of its index entry)
     */
    public record ModuleEntry(String name, String pluginType) {

        public ModuleEntry {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(pluginType, "pluginType");
        }
    }
}
