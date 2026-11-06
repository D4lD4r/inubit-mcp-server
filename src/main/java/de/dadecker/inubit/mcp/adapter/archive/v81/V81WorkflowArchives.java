package de.dadecker.inubit.mcp.adapter.archive.v81;

import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.ModuleIndexEntry;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.ModuleXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.WorkflowGroupXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.WorkflowXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.RawZip.RawEntry;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.RepositoryReference;
import de.dadecker.inubit.mcp.domain.model.WorkflowArchivePlan;
import de.dadecker.inubit.mcp.domain.model.WorkflowArchivePlan.ModuleEntry;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import de.dadecker.inubit.mcp.domain.port.WorkflowArchivePort;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.Set;
import java.util.stream.Collectors;

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
 *
 * <p>{@link SlicedExport#archive} builds the archive of one workflow (research R-7, FR-006): the
 * export's entries in their order and with their headers, restricted to
 * {@code archive.properties} and the files of the selected modules (bytes copied),
 * {@code workflow/workflow.xml} with the workflow's {@code WorkflowGroup} holding only its
 * unchanged {@code Workflow} element, {@code module/module.xml} with only the selected index
 * entries in their group and position order (no empty group), both built as
 * {@link ArchiveAssembler} does and serialized by {@link XmlNormalizer}, and a
 * {@code Repository.zip} of the {@code <path>.xml} and {@code <path>.dat} entries of the planned
 * repository files plus the directory entries the original has that are ancestors of a kept
 * entry, each unchanged, in original order, with the original archive comment. Secrets stay as
 * exported. Directory entries of the nested {@code Repository.zip} are read
 * ({@link ArchiveReader#allowingRepositoryDirectories()}), never synthesized.
 */
public final class V81WorkflowArchives implements WorkflowArchivePort {

    /**
     * The plug-in type of a Workflow Connector (research R-5): first taken from a REST log, confirmed
     * 2026-11-06 against the {@code ModuleGroupName} in a real {@code module/module.xml} of INUBIT
     * 8.1.14 (manual check T026).
     */
    static final String WORKFLOW_CONNECTOR = "Workflow Connector";

    private static final String TECHNICAL = "technical";
    private static final String PROPERTIES = "archive.properties";
    private static final String REPOSITORY = "Repository.zip";
    private static final String WORKFLOWS = "workflow/workflow.xml";
    private static final String MODULE_INDEX = "module/module.xml";
    private static final String DEFAULT_VERSION = "5.3";

    private final ArchiveReader reader;

    public V81WorkflowArchives() {
        this(ArchiveReader.allowingRepositoryDirectories());
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
        Map<String, WorkflowGroupXml> sources = new LinkedHashMap<>();
        for (WorkflowGroupXml workflows : export.workflowGroups()) {
            if (!workflows.name().equals(diagramGroup)) {
                continue;
            }
            for (WorkflowXml workflow : workflows.workflows()) {
                if (isTechnical(workflow.element())) {
                    plans.put(workflow.name(), plan(workflow, export, raw, index, group, owner));
                    sources.put(workflow.name(), new WorkflowGroupXml(workflows.name(),
                        workflows.attributes(), List.of(workflow)));
                }
            }
        }
        Optional<RawZip> repository = raw.entry(REPOSITORY).map(e -> RawZip.read(e.bytes()));
        return new Sliced(export, raw, repository, plans, sources);
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

    /**
     * One export, read once, with the plans of its workflows and, per workflow, its
     * {@code WorkflowGroup} holding only that workflow.
     */
    private static final class Sliced implements SlicedExport {

        private final ExportArchive export;
        private final RawZip raw;
        private final Optional<RawZip> repository;
        private final Map<String, WorkflowArchivePlan> plans;
        private final Map<String, WorkflowGroupXml> sources;

        Sliced(ExportArchive export, RawZip raw, Optional<RawZip> repository,
            Map<String, WorkflowArchivePlan> plans, Map<String, WorkflowGroupXml> sources) {
            this.export = export;
            this.raw = raw;
            this.repository = repository;
            this.plans = plans;
            this.sources = sources;
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
            WorkflowArchivePlan plan = plan(workflow);
            Set<String> modules = plan.modules().stream().map(ModuleEntry::name)
                .collect(Collectors.toSet());
            Set<String> moduleFiles = export.moduleFiles().values().stream()
                .filter(module -> modules.contains(module.name())).map(ModuleXml::entryName)
                .collect(Collectors.toSet());
            List<RawEntry> entries = new ArrayList<>();
            for (RawEntry entry : raw.entries()) {
                String name = entry.header().getName();
                switch (name) {
                    case PROPERTIES -> entries.add(entry);
                    case REPOSITORY -> entries.add(entry.withBytes(repository(plan)));
                    case WORKFLOWS -> entries.add(entry.withBytes(workflowDocument(workflow)));
                    case MODULE_INDEX -> entries.add(entry.withBytes(indexDocument(modules)));
                    default -> {
                        if (moduleFiles.contains(name)) {
                            entries.add(entry);
                        }
                    }
                }
            }
            return RawZip.write(entries, raw.comment());
        }

        private byte[] workflowDocument(String workflow) {
            WorkflowGroupXml group = sources.get(workflow);
            String version = group.workflows().get(0).context().documentVersion();
            return XmlNormalizer.normalize(ArchiveAssembler.workflowDocument(version,
                List.of(group)));
        }

        private byte[] indexDocument(Set<String> modules) {
            String version = export.moduleIndex().stream()
                .map(ModuleIndexEntry::documentVersion).findFirst().orElse(DEFAULT_VERSION);
            return XmlNormalizer.normalize(ArchiveAssembler.indexDocument(version,
                export.moduleIndex().stream().filter(entry -> modules.contains(entry.name()))
                    .toList()));
        }

        /**
         * The nested {@code Repository.zip} with the metadata and content entries of the planned
         * files and the original's directory entries that are ancestors of a kept entry.
         */
        private byte[] repository(WorkflowArchivePlan plan) {
            RawZip original = repository.orElseThrow();
            Set<String> wanted = new HashSet<>();
            plan.repositoryPaths().forEach(path -> {
                wanted.add(path.substring(1) + ".xml");
                wanted.add(path.substring(1) + ".dat");
            });
            List<String> files = original.entries().stream().map(e -> e.header().getName())
                .filter(wanted::contains).toList();
            List<RawEntry> kept = original.entries().stream().filter(entry -> {
                String name = entry.header().getName();
                return name.endsWith("/") ? files.stream().anyMatch(f -> f.startsWith(name))
                    : wanted.contains(name);
            }).toList();
            return RawZip.write(kept, original.comment());
        }
    }
}
