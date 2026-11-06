package de.dadecker.inubit.mcp.adapter.archive.v81;

import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.ModuleIndexEntry;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.ModuleXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.WorkflowGroupXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.WorkflowXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.RepositoryReference;
import de.dadecker.inubit.mcp.domain.model.WorkflowArchivePlan;
import de.dadecker.inubit.mcp.domain.model.WorkflowArchivePlan.ModuleEntry;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import de.dadecker.inubit.mcp.domain.port.WorkflowArchivePort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedSet;

/**
 * The 8.1 {@link WorkflowArchivePort} (feature 015, research R-3 – R-6).
 *
 * <p>{@link #slice} reads the export once as model ({@link ArchiveReader}) and as raw entries
 * ({@link RawZip}) and computes the plan of every technical workflow of the diagram group:
 *
 * <ul>
 *   <li>Modules (R-5): the {@code ModuleName} of every {@code WorkflowModule} element below the
 *       workflow, at any depth (e.g. below a {@code PartnerManagement} node), whose name has an
 *       index entry, in document order; then every index entry bound to the workflow by
 *       {@code WorkflowName}, in index order; each name once, with the plug-in type
 *       ({@code ModuleGroupName}) of its first index entry.
 *   <li>Missing modules: the names of {@code technical} nodes without an index entry, as the
 *       workspace export warns about them ({@link WorkspaceWriter}); other node kinds such as
 *       {@code Comment} or {@code PartnerManagement} carry no module.
 *   <li>Repository paths (R-6): the owner-root closure ({@link ArchiveReferences#closure}) of the
 *       references in the unredacted workflow element, the selected index entries and module
 *       files (their bytes as exported), restricted to the files of the export's
 *       {@code Repository.zip}.
 *   <li>Workflow Connector: a node whose module has the plug-in type {@link #WORKFLOW_CONNECTOR}.
 * </ul>
 */
public final class V81WorkflowArchives implements WorkflowArchivePort {

    /**
     * The plug-in type of a Workflow Connector. Unverified (research R-5): taken from a REST log,
     * not from a recorded {@code module/module.xml}; confirmed in the manual check (T026).
     */
    static final String WORKFLOW_CONNECTOR = "Workflow Connector";

    private static final String TECHNICAL = "technical";

    private final ArchiveReader reader;

    public V81WorkflowArchives() {
        this(new ArchiveReader());
    }

    V81WorkflowArchives(ArchiveReader reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    @Override
    public SlicedExport slice(byte[] diagramGroupExport, GroupId group, String owner,
        String diagramGroup) {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(diagramGroup, "diagramGroup");
        ExportArchive export = reader.read(diagramGroupExport);
        RawZip raw = RawZip.read(diagramGroupExport);
        Map<String, List<ModuleIndexEntry>> index = new LinkedHashMap<>();
        export.moduleIndex().forEach(entry -> index.computeIfAbsent(entry.name(),
            name -> new ArrayList<>()).add(entry));
        Map<String, WorkflowArchivePlan> plans = new LinkedHashMap<>();
        for (WorkflowGroupXml workflows : export.workflowGroups()) {
            if (!workflows.name().equals(diagramGroup)) {
                continue;
            }
            for (WorkflowXml workflow : workflows.workflows()) {
                if (isTechnical(workflow.element())) {
                    plans.put(workflow.name(), plan(workflow, export, raw, index, group, owner));
                }
            }
        }
        return new Sliced(export, raw, plans);
    }

    private static boolean isTechnical(Element workflow) {
        return workflow.attribute("workflowType").map(TECHNICAL::equals).orElse(true);
    }

    private static WorkflowArchivePlan plan(WorkflowXml workflow, ExportArchive export,
        RawZip raw, Map<String, List<ModuleIndexEntry>> index, GroupId group, String owner) {
        List<Element> nodes = new ArrayList<>();
        nodes(workflow.element(), nodes);
        SequencedSet<String> selected = new LinkedHashSet<>();
        SequencedSet<String> missing = new LinkedHashSet<>();
        boolean workflowConnector = false;
        for (Element node : nodes) {
            String name = node.child("ModuleName").map(Element::text).orElse(null);
            if (name == null) {
                continue;
            }
            List<ModuleIndexEntry> entries = index.get(name);
            if (entries != null) {
                selected.add(name);
                workflowConnector |= entries.get(0).pluginType().equals(WORKFLOW_CONNECTOR);
            } else if (node.attribute("moduleType").filter(TECHNICAL::equals).isPresent()) {
                missing.add(name);
            }
        }
        for (ModuleIndexEntry entry : export.moduleIndex()) {
            if (entry.element().child("WorkflowName").map(Element::text)
                .filter(workflow.name()::equals).isPresent()) {
                selected.add(entry.name());
            }
        }

        List<RepositoryReference> references = new ArrayList<>(ArchiveReferences.workflow(
            WorkspacePath.workflow(group, owner, workflow.diagramGroup(), workflow.name()),
            XmlNormalizer.normalize(workflow.element())));
        List<ModuleEntry> modules = new ArrayList<>();
        for (String name : selected) {
            List<ModuleIndexEntry> entries = index.get(name);
            String pluginType = entries.get(0).pluginType();
            modules.add(new ModuleEntry(name, pluginType));
            WorkspacePath indexPath = WorkspacePath.moduleIndex(group, owner, pluginType, name);
            for (ModuleIndexEntry entry : entries) {
                references.addAll(ArchiveReferences.indexEntry(indexPath,
                    XmlNormalizer.normalize(entry.element())));
            }
            ModuleXml module = export.moduleFiles().get(name);
            if (module != null) {
                // the module file as exported; ArchiveReader parsed these bytes already
                byte[] content = raw.entry(module.entryName()).orElseThrow().bytes();
                references.addAll(ArchiveReferences.moduleFile(
                    WorkspacePath.module(group, owner, pluginType, name), content));
            }
        }
        List<String> repositoryPaths = ArchiveReferences.closure(owner, references,
            export.repository().values(), group).included().stream()
            .filter(path -> export.repository().containsKey(path.substring(1)))
            .toList();
        return new WorkflowArchivePlan(workflow.name(), modules, List.copyOf(missing),
            repositoryPaths, workflowConnector);
    }

    /** The {@code WorkflowModule} elements below {@code element}, any depth, document order. */
    private static void nodes(Element element, List<Element> nodes) {
        for (Element child : element.elements()) {
            if (child.localName().equals("WorkflowModule")) {
                nodes.add(child);
            }
            nodes(child, nodes);
        }
    }

    /** One export, read once, with the plans of its workflows. */
    private static final class Sliced implements SlicedExport {

        private final ExportArchive export;
        private final RawZip raw;
        private final Map<String, WorkflowArchivePlan> plans;

        Sliced(ExportArchive export, RawZip raw, Map<String, WorkflowArchivePlan> plans) {
            this.export = export;
            this.raw = raw;
            this.plans = plans;
        }

        /** Names the workflows only; never content. */
        @Override
        public String toString() {
            return "SlicedExport[" + plans.keySet() + "]";
        }

        @Override
        public List<String> workflows() {
            return List.copyOf(plans.keySet());
        }

        @Override
        public WorkflowArchivePlan plan(String workflow) {
            WorkflowArchivePlan plan = plans.get(workflow);
            if (plan == null) {
                throw new IllegalArgumentException("No technical workflow " + workflow
                    + " in the diagram group");
            }
            return plan;
        }

        @Override
        public byte[] archive(String workflow) {
            plan(workflow);
            throw new UnsupportedOperationException("Building the archive comes with T008");
        }
    }
}
