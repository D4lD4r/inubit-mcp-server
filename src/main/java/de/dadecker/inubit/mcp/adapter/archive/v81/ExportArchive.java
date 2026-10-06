package de.dadecker.inubit.mcp.adapter.archive.v81;

import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Attribute;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A StartCLI export of INUBIT 8.1 in memory (data-model.md → Archive model, research D-4), as
 * {@link ArchiveReader} parsed it; the raw ZIP is not kept. It may still hold secret values:
 * only the redaction of a later task makes it writable.
 *
 * @param properties     {@code archive.properties} (e.g. {@code sourceVersion},
 *                       {@code operationId}), sorted by key
 * @param entries        the names of the ZIP entries in archive order, directory entries
 *                       included (e.g. the empty {@code workflow/} of a module-only export)
 * @param workflowGroups the diagram groups of {@code workflow/workflow.xml} in document order;
 *                       empty for a module-only export
 * @param moduleIndex    the entries of {@code module/module.xml} in document order
 * @param moduleFiles    the module files ({@code module/<lower-case name>.xml}) by module name,
 *                       in archive order
 * @param repository     the files of the nested {@code Repository.zip} by repository path
 *                       (e.g. {@code Root/OWNERS/xsd/msg.xsd}), in archive order
 */
public record ExportArchive(Map<String, String> properties, List<String> entries,
    List<WorkflowGroupXml> workflowGroups, List<ModuleIndexEntry> moduleIndex,
    Map<String, ModuleXml> moduleFiles, Map<String, RepositoryFile> repository) {

    public ExportArchive {
        properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        entries = List.copyOf(entries);
        workflowGroups = List.copyOf(workflowGroups);
        moduleIndex = List.copyOf(moduleIndex);
        moduleFiles = Collections.unmodifiableMap(new LinkedHashMap<>(moduleFiles));
        repository = Collections.unmodifiableMap(new LinkedHashMap<>(repository));
    }

    /** One {@code WorkflowGroup} (diagram group) of {@code workflow/workflow.xml}. */
    public record WorkflowGroupXml(String name, List<Attribute> attributes,
        List<WorkflowXml> workflows) {

        public WorkflowGroupXml {
            Objects.requireNonNull(name, "name");
            attributes = List.copyOf(attributes);
            workflows = List.copyOf(workflows);
        }
    }

    /**
     * One workflow: the {@code Workflow} element (later a workflow file of its own) and its
     * place in the export (later {@code .meta/}, D-4).
     */
    public record WorkflowXml(String name, String diagramGroup, Element element,
        WorkflowContext context) {

        public WorkflowXml {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(diagramGroup, "diagramGroup");
            Objects.requireNonNull(element, "element");
            Objects.requireNonNull(context, "context");
        }
    }

    /**
     * Where a workflow was: the {@code version} of {@code IBISWorkflow}, the attributes and
     * position of its {@code WorkflowGroup}, and its position within the group.
     */
    public record WorkflowContext(String documentVersion, List<Attribute> groupAttributes,
        int groupPosition, int position) {

        public WorkflowContext {
            Objects.requireNonNull(documentVersion, "documentVersion");
            groupAttributes = List.copyOf(groupAttributes);
        }
    }

    /**
     * One {@code Module} of the module index with its plugin type ({@code ModuleGroupName}), the
     * {@code version} of {@code IBISWorkflow} and its position (module group, then module).
     */
    public record ModuleIndexEntry(String name, String pluginType, Element element,
        String documentVersion, int groupPosition, int position) {

        public ModuleIndexEntry {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(pluginType, "pluginType");
            Objects.requireNonNull(element, "element");
            Objects.requireNonNull(documentVersion, "documentVersion");
        }
    }

    /**
     * One module file: the {@code Properties} element; name and plugin type come from the index
     * (a module file does not name its module, spike §2).
     */
    public record ModuleXml(String name, String pluginType, String entryName, Element element) {

        public ModuleXml {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(pluginType, "pluginType");
            Objects.requireNonNull(entryName, "entryName");
            Objects.requireNonNull(element, "element");
        }
    }

    /**
     * One repository file: its content ({@code <path>.dat}) and metadata ({@code <path>.xml},
     * a {@code Property} element of type {@code RepositoryFile}).
     */
    public record RepositoryFile(String path, byte[] content, Optional<Element> metadata) {

        public RepositoryFile {
            Objects.requireNonNull(path, "path");
            content = content.clone();
            metadata = metadata == null ? Optional.empty() : metadata;
        }

        /** A copy of the content. */
        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
